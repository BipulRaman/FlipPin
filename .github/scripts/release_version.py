import re
import sys


def release_version(tag: str) -> tuple[str, int]:
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Release tags must be plain semantic versions such as 1.0.0 (no v prefix or suffix)")
    major, minor, patch = map(int, tag.split("."))
    if minor > 99 or patch > 99:
        raise ValueError("Minor and patch versions must be below 100")
    code = major * 10000 + minor * 100 + patch
    if not 1 <= code <= 2100000000:
        raise ValueError("The derived versionCode must be between 1 and 2100000000")
    return tag, code


if __name__ == "__main__":
    try:
        name, code = release_version(sys.argv[1])
    except (ValueError, IndexError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
    print(f"name={name}")
    print(f"code={code}")
