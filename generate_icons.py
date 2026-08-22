from PIL import Image, ImageDraw, ImageChops
import os

src_img_path = os.path.join(os.path.dirname(__file__), "assets", "icon-source.png")
base_res_dir = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res")

legacy_sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

adaptive_sizes = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432
}

def make_circle(img):
    mask = Image.new('L', img.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0) + img.size, fill=255)
    result = img.copy()
    result.putalpha(mask)
    return result

if not os.path.exists(src_img_path):
    print("Source image not found!")
    exit(1)

img = Image.open(src_img_path).convert("RGBA")

# Get background color from the top-left pixel
bg_color = img.getpixel((0, 0))
hex_color = "#{:02x}{:02x}{:02x}".format(bg_color[0], bg_color[1], bg_color[2])

# Write ic_launcher_background.xml
values_dir = os.path.join(base_res_dir, "values")
if not os.path.exists(values_dir):
    os.makedirs(values_dir)

colors_xml_path = os.path.join(values_dir, "ic_launcher_background.xml")
with open(colors_xml_path, "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n')
    f.write('<resources>\n')
    f.write(f'    <color name="ic_launcher_background">{hex_color}</color>\n')
    f.write('</resources>\n')

# Find the bounding box of the non-background pixels to properly scale the logo
bg_img = Image.new("RGBA", img.size, bg_color)
diff = ImageChops.difference(img, bg_img)
bbox = diff.convert("L").point(lambda p: 255 if p > 10 else 0).getbbox()

if bbox:
    logo = img.crop(bbox)
else:
    logo = img

# For legacy icons, we want the logo to take up about 80% of the icon
for folder, size in legacy_sizes.items():
    folder_path = os.path.join(base_res_dir, folder)
    if not os.path.exists(folder_path):
        os.makedirs(folder_path)
    
    icon = Image.new("RGBA", (size, size), bg_color)
    
    logo_size = int(size * 0.8)
    ratio = min(logo_size / logo.width, logo_size / logo.height)
    new_w = int(logo.width * ratio)
    new_h = int(logo.height * ratio)
    scaled_logo = logo.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    offset_x = (size - new_w) // 2
    offset_y = (size - new_h) // 2
    
    icon.paste(scaled_logo, (offset_x, offset_y))
    icon.save(os.path.join(folder_path, "ic_launcher.png"), "PNG")
    
    round_img = make_circle(icon)
    round_img.save(os.path.join(folder_path, "ic_launcher_round.png"), "PNG")

# For adaptive icons, safe zone is 72dp out of 108dp (66.6%).
for folder, size in adaptive_sizes.items():
    folder_path = os.path.join(base_res_dir, folder)
    if not os.path.exists(folder_path):
        os.makedirs(folder_path)
    
    fg_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    
    logo_size = int(size * 0.6)
    ratio = min(logo_size / logo.width, logo_size / logo.height)
    new_w = int(logo.width * ratio)
    new_h = int(logo.height * ratio)
    scaled_logo = logo.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    data = scaled_logo.getdata()
    new_data = []
    for item in data:
        if abs(item[0]-bg_color[0]) < 10 and abs(item[1]-bg_color[1]) < 10 and abs(item[2]-bg_color[2]) < 10:
            new_data.append((255, 255, 255, 0))
        else:
            new_data.append(item)
    scaled_logo.putdata(new_data)
    
    offset_x = (size - new_w) // 2
    offset_y = (size - new_h) // 2
    
    fg_icon.paste(scaled_logo, (offset_x, offset_y), scaled_logo)
    fg_icon.save(os.path.join(folder_path, "ic_launcher_foreground.png"), "PNG")

print("Icons generated successfully.")
