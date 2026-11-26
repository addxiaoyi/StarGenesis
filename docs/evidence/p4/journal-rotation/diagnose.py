from pathlib import Path
import ctypes
import json

class Memory(ctypes.Structure):
    _fields_ = [('length', ctypes.c_ulong), ('load', ctypes.c_ulong)] + [(name, ctypes.c_ulonglong) for name in
        ('totalPhysical', 'availablePhysical', 'totalPageFile', 'availablePageFile', 'totalVirtual', 'availableVirtual', 'extendedVirtual')]

memory = Memory()
memory.length = ctypes.sizeof(memory)
ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory))
print(json.dumps({name: getattr(memory, name) for name, _ in memory._fields_}))
lines = Path('.work/radio-journal/focused-java21-retry.log').read_text(errors='replace').splitlines()
print('\n'.join(lines[-22:]))
print('\n'.join(line for line in lines if 'Caused by:' in line or 'OutOfMemory' in line or 'allocation' in line))
for index, line in enumerate(lines):
    if 'Caused by:' in line:
        print('\n'.join(lines[index:index + 16]))
crashes = sorted(Path('.').glob('hs_err_pid*.log'), key=lambda p: p.stat().st_mtime)
if crashes:
    print(crashes[-1])
    print('\n'.join(crashes[-1].read_text(errors='replace').splitlines()[:35]))
