import sys
from pathlib import Path

# Cho phép `import tb_reader` khi chạy pytest từ thư mục research/forecast-data.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
