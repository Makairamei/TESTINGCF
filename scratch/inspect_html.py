import re
import base64

html = open('D:/WEB/4/test_episode.html', encoding='utf-8').read()

print("--- ALL OPTIONS ---")
for match in re.finditer(r'<option[^>]*value="([^"]*)"[^>]*>(.*?)</option>', html):
    val = match.group(1)
    text = match.group(2).strip()
    print(f"Text: {text}")
    print(f"Raw Value: {val[:100]}...")
    if val:
        try:
            # Fix padding if needed
            padded_val = val + '=' * ((4 - len(val) % 4) % 4)
            decoded = base64.b64decode(padded_val).decode('utf-8', errors='ignore')
            print(f"Decoded: {decoded[:200]}")
        except Exception as e:
            print(f"Failed to decode base64: {e}")
    print("-" * 40)
