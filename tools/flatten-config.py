import sys, yaml
d = yaml.safe_load(open(sys.argv[1], encoding='utf-8'))
out = []
def walk(prefix, v):
    if isinstance(v, dict):
        for k, x in v.items():
            walk(prefix + ('.' if prefix else '') + str(k), x)
    elif isinstance(v, bool):
        out.append(f"{prefix}\tb\t{str(v).lower()}")
    elif isinstance(v, int):
        out.append(f"{prefix}\ti\t{v}")
    elif isinstance(v, list):
        out.append(f"{prefix}\tl\t" + "\x01".join(str(x) for x in v))
    else:
        out.append(f"{prefix}\ts\t{v}")
walk('', d)
open(sys.argv[2], 'w', encoding='utf-8').write("\n".join(out) + "\n")
print(len(out), "keys")
