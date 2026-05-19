# -*- coding: utf-8 -*-

from __future__ import print_function
from __future__ import absolute_import
from __future__ import unicode_literals

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

import python_atom_sdk as sdk



class PluginError(Exception):
    pass


def main():
    try:
        result = run_plugin(sdk.get_input())
        set_plugin_output(sdk.status.SUCCESS, "run succ", result)
        sdk.log.info("代码同步完成")
        sys.exit(0)
    except Exception as error:
        sdk.log.error(str(error))
        set_plugin_output(sdk.status.FAILURE, str(error), {})
        sys.exit(1)


def run_plugin(input_params):
    git_username = require_input(input_params, "GIT_USERNAME")
    git_token = require_input(input_params, "GIT_TOKEN")
    git_host = require_input(input_params, "GIT_HOST")
    repo_url = require_input(input_params, "KINGEYE_GIT_REPO")
    branch_value = require_input(input_params, "BRANCH")
    target_branch = parse_branch_name(branch_value)

    cache_dir = normalize_path(
        input_params.get("CACHE_DIR") or "${{ci.workspace}}/git-cache/kingeye",
        input_params,
    )
    target_dir = normalize_path(
        input_params.get("TARGET_DIR") or "${{ci.workspace}}/${{ci.build_num}}/kingeye_source",
        input_params,
    )
    default_work_dir = normalize_path(
        input_params.get("DEFAULT_WORK_DIR") or "${{ci.workspace}}",
        input_params,
    )

    validate_tools()
    validate_paths(cache_dir, target_dir, default_work_dir)

    sdk.log.info("目标分支: {}".format(target_branch))
    sdk.log.info("仓库地址: {}".format(repo_url))
    sdk.log.info("缓存目录: {}".format(cache_dir))
    sdk.log.info("目标目录: {}".format(target_dir))

    configure_git_credentials(git_username, git_token, git_host)
    sync_git_cache(repo_url, target_branch, cache_dir)
    commit_id = get_commit_id(cache_dir)
    rsync_to_target(cache_dir, target_dir)

    sdk.log.info("分支: {}".format(target_branch))
    sdk.log.info("Commit: {}".format(commit_id))
    sdk.log.info("工作目录: {}".format(target_dir))

    return {
        "targetBranch": target_branch,
        "commitId": commit_id,
        "targetDir": target_dir,
        "cacheDir": cache_dir,
    }


def require_input(input_params, key):
    value = str(input_params.get(key, "")).strip()
    if not value:
        raise PluginError("缺少必填参数: {}".format(key))
    return value


def parse_branch_name(branch_value):
    branch_text = str(branch_value).strip()
    if not branch_text:
        raise PluginError("branchName 解析失败")

    try:
        branch_data = json.loads(branch_text)
        if isinstance(branch_data, dict):
            branch_name = str(branch_data.get("branchName", "")).strip()
            if branch_name and branch_name.lower() != "null":
                return branch_name
    except ValueError:
        pass

    branch_match = re.search(r"branchName['\"]?\s*[:=]\s*['\"]?([^,}'\"]+)", branch_text)
    if branch_match:
        branch_name = branch_match.group(1).strip()
        if branch_name and branch_name.lower() != "null":
            return branch_name

    if "branchName" not in branch_text and branch_text.lower() != "null":
        return branch_text

    raise PluginError("branchName 解析失败")


def normalize_path(path_value, input_params):
    resolved_path = str(path_value).strip()
    replacements = {
        "${{ci.workspace}}": get_workspace(input_params),
        "${{ci.build_num}}": get_build_num(input_params),
    }
    for placeholder, value in replacements.items():
        resolved_path = resolved_path.replace(placeholder, value)
    return os.path.abspath(os.path.expanduser(resolved_path))


def get_workspace(input_params):
    return str(
        input_params.get("ci.workspace")
        or os.getenv("BK_CI_WORKSPACE")
        or os.getenv("WORKSPACE")
        or os.getcwd()
    )


def get_build_num(input_params):
    return str(
        input_params.get("ci.build_num")
        or input_params.get("pipeline.build.num")
        or os.getenv("BK_CI_BUILD_NUM")
        or "0"
    )


def validate_tools():
    for tool_name in ("git", "rsync"):
        if not shutil.which(tool_name):
            raise PluginError("未找到命令: {}".format(tool_name))


def validate_paths(cache_dir, target_dir, default_work_dir):
    target_path = Path(target_dir).resolve(strict=False)
    default_work_path = Path(default_work_dir).resolve(strict=False)
    cache_path = Path(cache_dir).resolve(strict=False)

    if target_path == default_work_path:
        raise PluginError("目标代码目录不能与默认工作目录相同: {}".format(target_path))
    if target_path == cache_path:
        raise PluginError("目标代码目录不能与 Git 缓存目录相同: {}".format(target_path))
    if default_work_path in target_path.parents:
        return
    sdk.log.warning("目标代码目录不在默认工作目录下，请确认配置符合预期: {}".format(target_path))


def configure_git_credentials(git_username, git_token, git_host):
    run_command(["git", "config", "--global", "credential.helper", "store"])
    credentials_path = Path.home() / ".git-credentials"
    credential_line = "https://{}:{}@{}".format(
        quote(git_username, safe=""),
        quote(git_token, safe=""),
        git_host.strip().replace("https://", "").replace("http://", "").rstrip("/"),
    )

    existing_lines = []
    if credentials_path.exists():
        existing_lines = credentials_path.read_text(encoding="utf-8").splitlines()

    normalized_host = credential_line.split("@", 1)[1]
    kept_lines = [line for line in existing_lines if not line.strip().endswith("@" + normalized_host)]
    kept_lines.append(credential_line)
    credentials_path.write_text("\n".join(kept_lines) + "\n", encoding="utf-8")
    os.chmod(str(credentials_path), 0o600)
    sdk.log.info("Git credential store 已配置: {}".format(normalized_host))


def sync_git_cache(repo_url, target_branch, cache_dir):
    if not os.path.isdir(os.path.join(cache_dir, ".git")):
        sdk.log.info("首次 clone 仓库...")
        os.makedirs(cache_dir, exist_ok=True)
        run_command(["git", "clone", "--origin", "origin", repo_url, cache_dir])

    sdk.log.info("同步远程仓库...")
    run_command(["git", "remote", "set-url", "origin", repo_url], cwd=cache_dir)
    run_command(["git", "fsck"], cwd=cache_dir, check=False)
    run_command(["git", "fetch", "origin", "--prune", "--tags"], cwd=cache_dir)
    run_command(["git", "reset", "--hard"], cwd=cache_dir)
    run_command(["git", "clean", "-fdx"], cwd=cache_dir)

    if local_branch_exists(cache_dir, target_branch):
        run_command(["git", "checkout", "-f", target_branch], cwd=cache_dir)
    else:
        run_command(["git", "checkout", "-B", target_branch], cwd=cache_dir)

    run_command(["git", "reset", "--hard", "origin/{}".format(target_branch)], cwd=cache_dir)
    run_command(["git", "clean", "-fdx"], cwd=cache_dir)


def local_branch_exists(cache_dir, target_branch):
    result = run_command(
        ["git", "show-ref", "--verify", "--quiet", "refs/heads/{}".format(target_branch)],
        cwd=cache_dir,
        check=False,
        capture_output=True,
    )
    return result.returncode == 0


def get_commit_id(cache_dir):
    result = run_command(["git", "rev-parse", "HEAD"], cwd=cache_dir, capture_output=True)
    return result.stdout.strip()


def rsync_to_target(cache_dir, target_dir):
    sdk.log.info("同步代码到工作目录...")
    os.makedirs(target_dir, exist_ok=True)
    run_command([
        "rsync",
        "-a",
        "--delete",
        "--exclude=.git",
        ensure_trailing_slash(cache_dir),
        ensure_trailing_slash(target_dir),
    ])


def ensure_trailing_slash(path_value):
    return path_value if path_value.endswith(os.sep) else path_value + os.sep


def run_command(command, cwd=None, check=True, capture_output=False):
    sdk.log.info("执行命令: {}".format(" ".join(mask_command(command))))
    result = subprocess.run(
        command,
        cwd=cwd,
        universal_newlines=True,
        stdout=subprocess.PIPE if capture_output else None,
        stderr=subprocess.PIPE if capture_output else None,
    )
    if check and result.returncode != 0:
        error_message = result.stderr.strip() if result.stderr else "命令执行失败"
        raise PluginError("{}: {}".format(error_message, " ".join(mask_command(command))))
    return result


def mask_command(command):
    masked_command = []
    for item in command:
        if "@" in item and "://" in item:
            masked_command.append(re.sub(r"(https?://)[^/@:]+:[^/@]+@", r"\1***:***@", item))
        else:
            masked_command.append(item)
    return masked_command


def set_plugin_output(status, message, data):
    output_fields = {}
    for key, value in data.items():
        output_fields[key] = {
            "type": sdk.output_field_type.STRING,
            "value": str(value),
        }
    sdk.set_output({
        "status": status,
        "message": message,
        "type": sdk.output_template_type.DEFAULT,
        "data": output_fields,
    })


if __name__ == "__main__":
    main()