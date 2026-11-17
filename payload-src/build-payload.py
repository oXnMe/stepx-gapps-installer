#!/usr/bin/env python3
"""把一段 dex 换进 inject.py 的副本，生成一个独立探针载荷。

inject.py 是上游原样件：boot.py 用固定契约校验它的输出，整条 Play 安装链都压在
这份契约上，所以它必须逐字节保持原样。任何额外动作（认证修补、开机解禁）都走
「副本」：只换内嵌 dex 和要加载的类名，其余 ptrace/JNI 骨架一个字不动。

用法：
  python3 payload-src/build-payload.py <classes.dex> <fqcn> <dst.py>
"""
import base64
import re
import sys

SRC = 'app/assets/payload/inject.py'
DEX_ANCHOR = re.compile(r"dex=base64\.b64decode\('[^']*'\)")
CLASS_ANCHOR = "'stepx.GmsProbe'"


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 1
    dex_path, fqcn, dst = argv[1], argv[2], argv[3]
    if not fqcn.startswith('stepx.'):
        raise SystemExit('探针必须放在 stepx 包下：%r' % fqcn)

    blob = base64.b64encode(open(dex_path, 'rb').read()).decode()
    text = open(SRC, encoding='utf-8').read()

    text, n = DEX_ANCHOR.subn("dex=base64.b64decode('%s')" % blob, text)
    assert n == 1, '内嵌 dex 锚点出现 %d 次，inject.py 结构变了' % n
    assert CLASS_ANCHOR in text, '类名锚点缺失，inject.py 结构变了'
    text = text.replace(CLASS_ANCHOR, repr(fqcn))

    compile(text, dst, 'exec')
    open(dst, 'w', encoding='utf-8').write(text)
    print('wrote %s (%d bytes, dex %d b64 chars)' % (dst, len(text), len(blob)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
