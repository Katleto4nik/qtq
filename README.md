# WebOptimizer (Fabric 1.21.11)

Client mod. Right Shift opens/closes the HUD (draggable by the title bar).
Config: `.minecraft/config/weboptimizer.json`

## Web
- Cobweb under the enemy, only while YOU stand on the ground (no jumping / swimming / flying).
- Only inside the reach zone (HUD slider, default 3.4 blocks).
- Legit flow: web goes into the hand (1 tick), then silent aim + place, item back ("Повертати меч").
- Prediction: movement history + physics (jump/fall/landing, walls, steps).
- No web: in water/lava, when the enemy is already in/near a web, 0.2 s after your own hit, shield/eating.
- Sprinting: the mod stops you, places the web, then you run again.

## Auto Drain
- Another player places water or lava near you -> the mod takes it with an EMPTY bucket in your hotbar.
- Default = instant: bucket into the hand, silent aim in 360 degrees, click and item back, all in the same tick.
- HUD sliders "Плавність Drain" and "Затримка Drain" add a slower head turn / reaction delay if you want it more human-like.

Silent rotation: your own camera never moves, only the rotation sent to the server.

Build: push to GitHub -> Actions -> artifact `WebOptimizer` (jar).
