package com.example.dreambot;

import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.interactive.GameObjects;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.item.GroundItems;
import org.dreambot.api.methods.map.Area;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.world.Worlds;
import org.dreambot.api.methods.world.World;
import org.dreambot.api.methods.worldhopper.WorldHopper;
import org.dreambot.api.methods.walking.impl.Walking;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.Category;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.wrappers.interactive.GameObject;
import org.dreambot.api.wrappers.items.GroundItem;

import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@ScriptManifest(
        author = "qe",
        name = "Ash Collector v2.0",
        version = 2.0,
        description = "Smart ash collector: targets top 5 populated F2P worlds, detects fires for ash prediction",
        category = Category.MONEYMAKING
)
public class AshCollector extends AbstractScript {

    private int ashesCollected = 0;
    private int ashesDeposited = 0;
    private long startTime;
    private String status = "Starting up";
    private Random random = new Random();

    private long lastWorldHop = 0;
    private long worldHopInterval = 0;
    private static final long MIN_WORLD_TIME = 3 * 60 * 1000L;
    private static final long MAX_WORLD_TIME = 8 * 60 * 1000L;
    private static final int MIN_POP = 50;
    private int lastWorld = -1;

    private long lastAshScan = 0;
    private static final long SCAN_DELAY = 1500L;
    private int consecutiveEmptyScans = 0;
    private static final int HOP_THRESHOLD = 20;
    private static final int MIN_FIRES = 5;

    private long lastFireScan = 0;
    private static final long FIRE_DELAY = 3000L;
    private int activeFiresDetected = 0;
    private int totalFiresThisSession = 0;
    private static final String[] FIRE_OBJECT_NAMES = {"Fire"};

    private static final Area GRAND_EXCHANGE_AREA = new Area(
            new Tile(3142, 3468, 0),
            new Tile(3189, 3514, 0)
    );

    private static final Area GRAND_EXCHANGE_CENTER = new Area(
            new Tile(3161, 3483, 0),
            new Tile(3167, 3487, 0)
    );

    private static final Area GRAND_EXCHANGE_BANK = new Area(3161, 3491, 3168, 3485, 0);

    private static final List<Integer> F2P_WORLDS = Arrays.asList(
            301, 308, 316, 326, 335, 371, 379, 380, 382, 383, 384, 393, 394, 397, 398, 399, 417, 418, 430, 431, 432, 433, 434, 435, 436, 437, 438, 439, 440, 451, 452, 453, 454, 455, 456, 457, 458
    );

    @Override
    public void onStart() {
        startTime = System.currentTimeMillis();
        log("Ash Collector started");
        log("Need " + MIN_FIRES + "+ fires to wait, otherwise hop");
        log("Targeting high population F2P worlds");

        scheduleNextWorldHop();

        World currentWorld = Worlds.getCurrent();
        if (currentWorld != null) {
            boolean isF2P = F2P_WORLDS.contains(currentWorld.getWorld());
            log("Current world: " + currentWorld.getWorld() + " (Pop: " + currentWorld.getPopulation() + ")" +
                (isF2P ? " F2P" : " NOT F2P"));

            if (!isF2P) {
                log("Non-F2P world, will hop soon");
            }
        }

        log("Starting at Grand Exchange");
        log("Will hop from worlds with under " + MIN_FIRES + " fires");
    }

    @Override
    public int onLoop() {
        try {
            // Update status for paint
            if (Players.getLocal() == null) {
                status = "Loading...";
                return 2000;
            }

            // Check if we need to hop worlds
            if (shouldHopWorld()) {
                return performWorldHop();
            }

            // If inventory is full, bank the ashes
            if (Inventory.isFull()) {
                return bankAshes();
            }

            // If not at Grand Exchange, walk there
            if (!isAtGrandExchange()) {
                return walkToGrandExchange();
            }

            // Scan for fires to predict ash locations
            scanForFires();

            // Main collection logic
            return collectAshes();

        } catch (Exception e) {
            log("Error in main loop: " + e.getMessage());
            status = "Error: " + e.getMessage();
            return 5000;
        }
    }

    private int collectAshes() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastAshScan < SCAN_DELAY) {
            status = "Waiting to scan for ashes...";
            return 1000;
        }

        lastAshScan = currentTime;

        // Find ashes on ground - adapted from SafeCombatActivity's ground item detection
        GroundItem ashes = GroundItems.closest(groundItem ->
                groundItem != null &&
                "Ashes".equals(groundItem.getName()) &&
                groundItem.distance() < 15 &&
                GRAND_EXCHANGE_AREA.contains(groundItem.getTile()) &&
                !Inventory.isFull()
        );

        if (ashes != null) {
            status = "Collecting ashes at distance " + (int)ashes.distance();
            consecutiveEmptyScans = 0;

            log("Found ashes at " + ashes.getTile() + " (distance: " + (int)ashes.distance() + ")");

            if (ashes.interact("Take")) {
                // Wait for pickup animation
                Sleep.sleepUntil(() -> !ashes.exists() || Inventory.isFull(), 3000);

                // Check if we actually picked it up
                int currentAshCount = Inventory.count("Ashes");
                if (currentAshCount > ashesCollected - ashesDeposited) {
                    ashesCollected++;
                    log("Collected ash #" + ashesCollected + "! Current inventory: " + currentAshCount);
                }

                consecutiveEmptyScans = 0;
                status = "Ash collected! Continuing collection...";

                return random.nextInt(800) + 400;
            } else {
                log("Failed to interact with ashes, retrying...");
                return 2000;
            }
        } else {
            consecutiveEmptyScans++;

            String fireInfo = activeFiresDetected > 0 ? " (" + activeFiresDetected + " fires)" : "";
            status = "Looking for ashes... (" + consecutiveEmptyScans + "s no ashes)" + fireInfo;
            if (activeFiresDetected > 0) {
                GameObject nearestFire = GameObjects.closest(obj ->
                    obj != null &&
                    Arrays.asList(FIRE_OBJECT_NAMES).contains(obj.getName()) &&
                    GRAND_EXCHANGE_AREA.contains(obj.getTile())
                );

                if (nearestFire != null && Players.getLocal().getTile().distance(nearestFire.getTile()) > 3) {
                    status = "Moving closer to fires for ash collection";
                    Walking.walk(nearestFire.getTile());
                    Sleep.sleep(800, 1200);
                }
            } else {
                Tile centerTile = new Tile(3165, 3491, 0); // Center of expanded GE area
                if (Players.getLocal().getTile().distance(centerTile) > 5) {
                    status = "No fires detected, moving to center";
                    Walking.walk(centerTile);
                    Sleep.sleep(800, 1200);
                }
            }

            int hopThreshold;

            if (activeFiresDetected == 0) {
                hopThreshold = HOP_THRESHOLD / 2;
            } else if (activeFiresDetected < MIN_FIRES) {
                hopThreshold = HOP_THRESHOLD;
            } else {
                hopThreshold = HOP_THRESHOLD * 3;
            }

            if (consecutiveEmptyScans >= hopThreshold) {
                if (activeFiresDetected == 0) {
                    log("No fires - hopping");
                } else if (activeFiresDetected < MIN_FIRES) {
                    log("Only " + activeFiresDetected + " fires (need " + MIN_FIRES + "+) - hopping");
                } else {
                    log("No ashes despite " + activeFiresDetected + " fires - trying new world");
                }

                // Execute immediate world hop instead of just scheduling
                return performWorldHop();
            }

            return 2000;
        }
    }

    private int bankAshes() {
        status = "Banking ashes - inventory full";

        if (!GRAND_EXCHANGE_BANK.contains(Players.getLocal())) {
            log("Walking to bank");
            Walking.walk(GRAND_EXCHANGE_BANK.getCenter());
            Sleep.sleepUntil(() -> GRAND_EXCHANGE_BANK.contains(Players.getLocal()), 8000);
            return 2000;
        }

        if (!Bank.isOpen()) {
            log("Opening bank");
            if (Bank.open()) {
                Sleep.sleepUntil(Bank::isOpen, 5000);
            } else {
                log("Failed to open bank - retrying");
                return 3000;
            }
        }

        if (Bank.isOpen()) {
            int ashesInInventory = Inventory.count("Ashes");
            log("Banking " + ashesInInventory + " ashes");

            if (Bank.depositAllItems()) {
                ashesDeposited += ashesInInventory;
                log("Deposited " + ashesInInventory + " ashes. Total deposited: " + ashesDeposited);

                Sleep.sleep(600, 1200);
                Bank.close();

                status = "Ashes banked, returning to collection";
                return 1000;
            } else {
                log("Failed to deposit ashes");
                return 2000;
            }
        }

        return 2000;
    }

    private int walkToGrandExchange() {
        status = "Walking to Grand Exchange";

        Tile playerTile = Players.getLocal().getTile();
        if (playerTile == null) {
            return 2000;
        }

        if (playerTile.distance(GRAND_EXCHANGE_CENTER.getCenter()) < 20) {
            log("Walking to Grand Exchange center");
            if (Walking.walk(GRAND_EXCHANGE_CENTER.getCenter())) {
                Sleep.sleepUntil(() -> GRAND_EXCHANGE_AREA.contains(Players.getLocal().getTile()), 8000);
            }
        } else {
            log("Too far from Grand Exchange, walking there");
            if (Walking.walk(GRAND_EXCHANGE_CENTER.getCenter())) {
                Sleep.sleepUntil(() -> GRAND_EXCHANGE_AREA.contains(Players.getLocal().getTile()), 15000);
            }
        }

        return 2000;
    }

    private boolean isAtGrandExchange() {
        return Players.getLocal() != null &&
               Players.getLocal().getTile() != null &&
               GRAND_EXCHANGE_AREA.contains(Players.getLocal().getTile());
    }

    private boolean shouldHopWorld() {
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastWorldHop) >= worldHopInterval;
    }

    private int performWorldHop() {
        if (Bank.isOpen()) {
            return 1000;
        }

        status = "Finding highest population F2P world";

        World currentWorld = Worlds.getCurrent();
        int currentWorldId = currentWorld != null ? currentWorld.getWorld() : 0;

        int newWorldId = findBestPopulationWorld(currentWorldId);

        if (newWorldId == -1) {
            log("No good worlds found, using random");
            List<Integer> availableWorlds = F2P_WORLDS;
            newWorldId = availableWorlds.get(random.nextInt(availableWorlds.size()));
        }

        log("Hopping to world " + newWorldId);

        final int targetWorldId = newWorldId;
        if (WorldHopper.hopWorld(newWorldId)) {
            Sleep.sleepUntil(() -> Worlds.getCurrent() != null &&
                                   Worlds.getCurrent().getWorld() == targetWorldId, 10000);

            lastWorldHop = System.currentTimeMillis();
            lastWorld = newWorldId;
            consecutiveEmptyScans = 0;
            scheduleNextWorldHop();

            log("Hopped to world " + newWorldId);
            return 3000; // Wait a bit after world hop
        } else {
            log("Failed to hop to world " + newWorldId);
            scheduleNextWorldHop();
            return 5000;
        }
    }

    private int findBestPopulationWorld(int currentWorld) {
        try {
            List<World> f2pWorlds = Worlds.f2p();

            if (f2pWorlds == null || f2pWorlds.isEmpty()) {
                log("No F2P worlds available from API");
                return -1;
            }

            f2pWorlds.sort((w1, w2) -> Integer.compare(w2.getPopulation(), w1.getPopulation()));

            for (World world : f2pWorlds.subList(0, Math.min(5, f2pWorlds.size()))) {
                if (world.getPopulation() > MIN_POP &&
                    world.getWorld() != currentWorld &&
                    world.getWorld() != lastWorld &&
                    world.getMinimumLevel() == 0 &&
                    !world.isPVP() &&
                    !world.isHighRisk()) {

                    log("Best world: " + world.getWorld() + " (pop: " + world.getPopulation() + ")");
                    return world.getWorld();
                }
            }

            log("No good worlds in top 5, trying all");

            for (World world : f2pWorlds) {
                if (world.getPopulation() > MIN_POP &&
                    world.getWorld() != currentWorld &&
                    world.getWorld() != lastWorld &&
                    world.getMinimumLevel() == 0 &&
                    !world.isPVP() &&
                    !world.isHighRisk()) {

                    log("Using world: " + world.getWorld() + " (pop: " + world.getPopulation() + ")");
                    return world.getWorld();
                }
            }

            return -1;

        } catch (Exception e) {
            log("Error finding best population world: " + e.getMessage());
            return -1;
        }
    }

    private void scanForFires() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastFireScan < FIRE_DELAY) {
            return;
        }

        lastFireScan = currentTime;

        try {
            int fireCount = 0;
            for (String fireType : FIRE_OBJECT_NAMES) {
                List<GameObject> fires = GameObjects.all(obj ->
                    obj != null &&
                    obj.getName().equals(fireType) &&
                    GRAND_EXCHANGE_AREA.contains(obj.getTile()) &&
                    obj.getTile().distance(Players.getLocal().getTile()) < 15
                );
                fireCount += fires.size();
            }

            activeFiresDetected = fireCount;
            totalFiresThisSession = Math.max(totalFiresThisSession, fireCount);

            if (fireCount > 0) {
                log("Fires: " + fireCount + " active (max: " + totalFiresThisSession + ")");
            }

        } catch (Exception e) {
            log("Error during fire scan: " + e.getMessage());
        }
    }

    private void scheduleNextWorldHop() {
        worldHopInterval = MIN_WORLD_TIME + (long)(random.nextDouble() * (MAX_WORLD_TIME - MIN_WORLD_TIME));
        long minutes = worldHopInterval / (60 * 1000);
        log("Next world hop scheduled in " + minutes + " minutes");
    }

    @Override
    public void onPaint(Graphics2D g) {
        // Match main AIO paint location and style exactly
        int panelX = 0;
        int panelY = 335;
        int panelWidth = 515;
        int panelHeight = 187;

        // Background - same as main AIO
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(panelX, panelY, panelWidth, panelHeight);

        // Border - same as main AIO
        g.setColor(new Color(60, 60, 60, 200));
        g.setStroke(new BasicStroke(2));
        g.drawRect(panelX, panelY, panelWidth, panelHeight);

        g.setFont(new Font("Arial", Font.BOLD, 12));
        g.setColor(Color.WHITE);
        g.drawString("Ash Collector", panelX + 10, panelY + 18);

        // Position content within the panel like main AIO
        int yPos = panelY + 40; // Start below title
        g.setFont(new Font("Arial", Font.PLAIN, 12));
        g.setColor(new Color(0, 191, 255)); // Same blue as main AIO

        long runtime = System.currentTimeMillis() - startTime;
        String runtimeStr = formatTime(runtime);

        g.drawString("Runtime: " + runtimeStr, panelX + 10, yPos);
        yPos += 18;

        g.drawString("Collected: " + ashesCollected, panelX + 10, yPos);
        yPos += 18;

        g.drawString("Banked: " + ashesDeposited, panelX + 10, yPos);
        yPos += 18;

        int totalProfit = ashesDeposited * 65;
        int profitPerHour = runtime > 0 ? (int)(totalProfit / (runtime / 3600000.0)) : 0;

        g.drawString("Profit: " + profitPerHour + " gp/hr", panelX + 10, yPos);
        yPos += 18;

        World currentWorld = Worlds.getCurrent();
        if (currentWorld != null) {
            g.drawString("World: " + currentWorld.getWorld() + " (Pop: " + currentWorld.getPopulation() + ")", panelX + 10, yPos);
            yPos += 18;
            g.drawString("Fires: " + activeFiresDetected, panelX + 10, yPos);
            yPos += 18;
        }

        g.setColor(Color.LIGHT_GRAY);
        String shortStatus = status.length() > 60 ? status.substring(0, 57) + "..." : status;
        g.drawString("Status: " + shortStatus, panelX + 10, yPos);
    }

    private String formatTime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

    @Override
    public void onExit() {
        log("Ash Collector stopping...");
        log("Final Statistics:");
        log("- Ashes Collected: " + ashesCollected);
        log("- Ashes Banked: " + ashesDeposited);
        log("- Estimated Profit: " + (ashesDeposited * 65) + " gp");

        long runtime = System.currentTimeMillis() - startTime;
        log("- Runtime: " + formatTime(runtime));

        log("Thank you for using Ash Collector!");
    }
}