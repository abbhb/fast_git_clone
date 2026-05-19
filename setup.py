# -*- coding: utf-8 -*-

import os

from setuptools import find_packages, setup

BASE_DIR = os.path.realpath(os.path.dirname(__file__))


def parse_requirements():
    reqs = []
    requirements_path = os.path.join(BASE_DIR, "requirements.txt")
    if os.path.isfile(requirements_path):
        with open(requirements_path, "r", encoding="utf-8") as requirements_file:
            for line in requirements_file.readlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    reqs.append(line)
    return reqs


def get_version():
    version_path = os.path.join(BASE_DIR, "version.txt")
    if os.path.exists(version_path):
        with open(version_path, "r", encoding="utf-8") as version_file:
            return version_file.read().strip()
    return "1.0.1"


if __name__ == "__main__":
    setup(
        version=get_version(),
        name="fast_git_clone",
        description="BlueKing CI plugin for cached git clone and workspace sync",
        packages=find_packages(),
        package_data={"": ["*.txt", "*.TXT", "*.JS", "test/*"]},
        install_requires=parse_requirements(),
        entry_points={"console_scripts": ["fast_git_clone = fast_git_clone.command_line:main"]},
        author="bkci",
        author_email="bkci@example.com",
        license="Copyright(c) Tencent All Rights Reserved.",
    )