import os
from PIL import Image, ImageDraw

def main():
    source_path = r"d:\BluetoothShareApp\www\icon.png"
    res_dir = r"d:\BluetoothShareApp\android\app\src\main\res"
    
    if not os.path.exists(source_path):
        print(f"Error: Source image not found at {source_path}")
        return
        
    img = Image.open(source_path)
    
    # Define densities and sizes
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192
    }
    
    for folder, size in densities.items():
        target_folder = os.path.join(res_dir, folder)
        os.makedirs(target_folder, exist_ok=True)
        
        # 1. Generate square icon
        square_img = img.resize((size, size), Image.Resampling.LANCZOS)
        square_path = os.path.join(target_folder, "ic_launcher.png")
        square_img.save(square_path, "PNG")
        print(f"Generated square launcher icon: {square_path}")
        
        # 2. Generate circular round icon
        circular_img = square_img.convert("RGBA")
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)
        
        round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_img.paste(circular_img, (0, 0), mask=mask)
        
        round_path = os.path.join(target_folder, "ic_launcher_round.png")
        round_img.save(round_path, "PNG")
        print(f"Generated round launcher icon: {round_path}")

if __name__ == "__main__":
    main()
