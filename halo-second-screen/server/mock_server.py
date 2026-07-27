"""
Halo Second-Screen Companion - Mock Data Server
-------------------------------------------------
Simulates live game-state telemetry (health, shields, ammo, radar contacts,
player position/heading) and broadcasts it over WebSocket as JSON, at a
fixed tick rate, to any connected Android client(s).

This lets you build and test the full pipeline (PC -> network -> Android HUD)
before any real MCC memory-reading or DLL-injection hook exists. Once you
have real data, you swap `generate_fake_state()` for a function that reads
actual game memory/events and the rest of this server stays the same.

Requires:
    pip install websockets

Run:
    python mock_server.py
    (defaults to 0.0.0.0:8765 - connect from Android using your PC's LAN IP)
"""

import asyncio
import json
import math
import random
import time
from dataclasses import dataclass, field, asdict

import websockets
from websockets.server import serve

HOST = "0.0.0.0"
PORT = 8765
TICK_HZ = 10  # updates per second sent to clients


@dataclass
class Blip:
    id: str
    x: float  # relative to player, -1.0 to 1.0
    y: float
    kind: str  # "enemy", "ally", "objective"


@dataclass
class GameState:
    health: float = 100.0
    shields: float = 100.0
    ammo_current: int = 32
    ammo_reserve: int = 96
    weapon: str = "MA5B Assault Rifle"
    heading_deg: float = 0.0
    position: dict = field(default_factory=lambda: {"x": 0.0, "y": 0.0, "z": 0.0})
    radar_contacts: list = field(default_factory=list)
    objective: str = "Find a way to the surface"
    timestamp: float = 0.0


class FakeStateGenerator:
    """Produces plausible, slowly-evolving fake game state each tick."""

    def __init__(self):
        self.state = GameState()
        self._t = 0.0

    def tick(self, dt: float) -> GameState:
        self._t += dt
        s = self.state

        # Shields regen slowly, health stays put unless "damaged"
        if random.random() < 0.02:
            s.shields = max(0.0, s.shields - random.uniform(5, 25))
        else:
            s.shields = min(100.0, s.shields + dt * 5)

        if random.random() < 0.005:
            s.health = max(0.0, s.health - random.uniform(5, 15))

        # Ammo drains occasionally (simulate firing), reloads when empty
        if random.random() < 0.15:
            s.ammo_current = max(0, s.ammo_current - 1)
        if s.ammo_current == 0 and s.ammo_reserve > 0:
            reload_amt = min(32, s.ammo_reserve)
            s.ammo_current = reload_amt
            s.ammo_reserve -= reload_amt

        # Heading slowly rotates, position drifts forward
        s.heading_deg = (s.heading_deg + dt * 15) % 360
        rad = math.radians(s.heading_deg)
        s.position["x"] += math.cos(rad) * dt * 2
        s.position["y"] += math.sin(rad) * dt * 2

        # Radar contacts orbit the player randomly
        if random.random() < 0.02 or not s.radar_contacts:
            s.radar_contacts = [
                Blip(
                    id=f"c{i}",
                    x=random.uniform(-1, 1),
                    y=random.uniform(-1, 1),
                    kind=random.choice(["enemy", "ally", "objective"]),
                ).__dict__
                for i in range(random.randint(0, 4))
            ]

        s.timestamp = time.time()
        return s


async def broadcast(state_gen: FakeStateGenerator, clients: set):
    dt = 1.0 / TICK_HZ
    while True:
        await asyncio.sleep(dt)
        if not clients:
            continue
        state = state_gen.tick(dt)
        payload = json.dumps(asdict(state))
        stale = set()
        for ws in clients:
            try:
                await ws.send(payload)
            except websockets.ConnectionClosed:
                stale.add(ws)
        clients -= stale


async def handler(websocket, clients: set):
    clients.add(websocket)
    print(f"[+] Client connected ({len(clients)} total)")
    try:
        async for _ in websocket:
            pass  # this server is broadcast-only; ignore incoming messages
    finally:
        clients.discard(websocket)
        print(f"[-] Client disconnected ({len(clients)} total)")


async def main():
    clients: set = set()
    state_gen = FakeStateGenerator()

    async with serve(lambda ws: handler(ws, clients), HOST, PORT):
        print(f"Mock Halo companion server running on ws://{HOST}:{PORT}")
        print("Find your PC's LAN IP (ipconfig/ifconfig) and point the Android app at it.")
        await broadcast(state_gen, clients)


if __name__ == "__main__":
    asyncio.run(main())
