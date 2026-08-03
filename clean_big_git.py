import os
import subprocess
import sys

def run(cmd):
    print(f"\n> {cmd}")
    res = subprocess.run(cmd, shell=True, text=True)
    if res.returncode != 0:
        print(f"命令失败，退出码 {res.returncode}")
        sys.exit(1)

def main():
    if not os.path.exists(".git"):
        print("错误：脚本放在项目根目录运行（和.git同级）")
        return

    print("=== 开始清除历史中所有sherpa大文件 ===")

    # 1. 删除模型文件夹全历史
    run(
        'git filter-branch --force --index-filter '
        '"git rm -r --cached --ignore-unmatch app/src/main/assets/sherpa-onnx-streaming-paraformer-bilingual-zh-en" '
        '--prune-empty --tag-name-filter cat -- --all'
    )

    # 2. 删除aar包历史
    run(
        'git filter-branch --force --index-filter '
        '"git rm --cached --ignore-unmatch app/libs/sherpa-onnx-1.12.11.aar" '
        '--prune-empty --tag-name-filter cat -- --all'
    )

    # 3. 全局删除所有.onnx文件历史
    run(
        'git filter-branch --force --index-filter '
        '"git rm --cached --ignore-unmatch *.onnx **/*.onnx" '
        '--prune-empty --tag-name-filter cat -- --all'
    )

    # 4. 删除jniLibs下so库历史
    run(
        'git filter-branch --force --index-filter '
        '"git rm --cached --ignore-unmatch app/src/main/jniLibs/*.so app/src/main/jniLibs/*/*.so" '
        '--prune-empty --tag-name-filter cat -- --all'
    )

    # 5. 回收git垃圾，彻底删干净
    run("git reflog expire --expire=now --all")
    run("git gc --prune=now --aggressive")

    print("\n✅ 历史大文件清理完毕！")
    print("接下来执行推送命令：")
    print("git push origin main --force-with-lease")

if __name__ == "__main__":
    main()