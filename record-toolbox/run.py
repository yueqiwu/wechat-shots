"""开发/打包用的启动器：python run.py [--headless] [--debug]"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.main import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
