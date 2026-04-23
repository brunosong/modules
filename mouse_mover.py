import pyautogui
import time
import random

pyautogui.FAILSAFE = True  # 마우스를 좌상단 모서리로 이동하면 긴급 종료

print("마우스 자동 이동 시작 (종료: Ctrl+C)")

while True:
    x, y = pyautogui.position()
    offset = random.choice([-3, -2, -1, 1, 2, 3])
    pyautogui.moveRel(offset, offset, duration=0.3)
    time.sleep(30)
