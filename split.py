import sys

with open('D:/MyMusic - Copy (3)/app/src/main/java/com/example/mymusic/MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('package com.example.mymusic', 'package com.auralis.app')
text = text.replace('import com.example.mymusic.', 'import com.auralis.app.')
text = text.replace('"com.example.mymusic.SYNC_COMPLETED"', '"com.auralis.app.SYNC_COMPLETED"')

lines = text.split('\n')

for i, line in enumerate(lines):
    if line.startswith('data class CachedAudioInfo'):
        audio_cache_start = i
    if line.startswith('private fun audioReadPermission'):
        main_activity_start = i
    if line.startswith('fun MusicAppScreen'):
        music_app_start = i
    if line.startswith('fun FullScreenPlayer'):
        full_screen_start = i

print(f'audio_cache_start: {audio_cache_start}')
print(f'main_activity_start: {main_activity_start}')
print(f'music_app_start: {music_app_start}')
print(f'full_screen_start: {full_screen_start}')

imports = [l + '\n' for l in lines[0:audio_cache_start-3]]
audio_cache = [l + '\n' for l in lines[audio_cache_start-3:main_activity_start-4]]
main_activity = [l + '\n' for l in lines[main_activity_start-4:music_app_start-3]]
music_app_screen = [l + '\n' for l in lines[music_app_start-3:full_screen_start-1]]
full_screen_player = [l + '\n' for l in lines[full_screen_start-1:]]

with open('D:/MyMusic - Copy (3)/app/src/main/java/com/example/mymusic/AudioCache.kt', 'w', encoding='utf-8') as f:
    f.writelines(imports + audio_cache)

with open('D:/MyMusic - Copy (3)/app/src/main/java/com/example/mymusic/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.writelines(imports + main_activity)

with open('D:/MyMusic - Copy (3)/app/src/main/java/com/example/mymusic/MusicAppScreen.kt', 'w', encoding='utf-8') as f:
    f.writelines(imports + music_app_screen)

with open('D:/MyMusic - Copy (3)/app/src/main/java/com/example/mymusic/FullScreenPlayer.kt', 'w', encoding='utf-8') as f:
    f.writelines(imports + full_screen_player)

print('Done!')