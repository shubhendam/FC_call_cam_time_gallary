import os

def print_tree(start_path, prefix=""):
    items = sorted(os.listdir(start_path))
    for index, item in enumerate(items):
        path = os.path.join(start_path, item)
        connector = "└── " if index == len(items) - 1 else "├── "
        print(prefix + connector + item)
        if os.path.isdir(path):
            extension = "    " if index == len(items) - 1 else "│   "
            print_tree(path, prefix + extension)

if __name__ == "__main__":
    current_dir = os.path.dirname(os.path.abspath(__file__))
    print(os.path.basename(current_dir))
    print_tree(current_dir)
