from PIL import Image
import os

def process_icon(src_path, dst_path):
    if not os.path.exists(src_path):
        return
    img = Image.open(src_path).convert("RGBA")
    
    # The original image might have a white/transparent border.
    # We want to crop the central part and scale it up so it fills the adaptive icon foreground (108x108 dp equivalent).
    # Typically, the icon is in the center. Let's find the bounding box of non-transparent/non-white pixels.
    
    # Or simpler: just scale it up by 1.5x and crop the center.
    width, height = img.size
    
    # Let's just scale the image up so the icon fills the frame.
    # We'll scale by 1.4x and crop to original size.
    new_width = int(width * 1.4)
    new_height = int(height * 1.4)
    
    img_resized = img.resize((new_width, new_height), Image.Resampling.LANCZOS)
    
    left = (new_width - width) / 2
    top = (new_height - height) / 2
    right = (new_width + width) / 2
    bottom = (new_height + height) / 2
    
    img_cropped = img_resized.crop((left, top, right, bottom))
    
    img_cropped.save(dst_path, "PNG")

# Process all ic_launcher_foreground.png in mipmap folders
base_dir = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res")
for root, dirs, files in os.walk(base_dir):
    if "mipmap" in root:
        for file in files:
            if file == "ic_launcher_foreground.png":
                path = os.path.join(root, file)
                print(f"Processing {path}")
                process_icon(path, path)
                
        # Also process ic_launcher.png and ic_launcher_round.png to be safe?
        # Actually, adaptive icons use ic_launcher_foreground.png.
        # But legacy icons use ic_launcher.png. If legacy icons have white borders, maybe we should scale them too?
        # Let's scale ic_launcher.png and ic_launcher_round.png as well.
        for file in files:
            if file in ["ic_launcher.png", "ic_launcher_round.png"]:
                path = os.path.join(root, file)
                print(f"Processing {path}")
                process_icon(path, path)
