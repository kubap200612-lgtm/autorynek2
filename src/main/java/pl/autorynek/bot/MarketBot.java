package pl.autorynek.bot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.data.AutoRynekConfig.Settings;
import pl.autorynek.data.Stats;
import pl.autorynek.data.WatchedItem;
import pl.autorynek.ui.ScreenHooks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maszyna stanow bota. Dziala w kazdym ticku klienta (20 razy/s).
 * Tryby: ANALYSIS (liczenie cen skupu) -> BUYING (petla odswiezania i kupowania).
 */
public class MarketBot {
    public enum Mode { OFF, ANALYSIS, BUYING }

    private enum Step { OPEN, WAIT_SCREEN, SETTLE, SORT_PLAN, SORT_CLICK, SORT_CHECK, SCAN, WAIT_CONFIRM }

    private record Offer(int slot, long price) {}

    private record Average(long price, int count, String details) {}

    private final AutoRynekConfig cfg;

    private Mode mode = Mode.OFF;
    private Step step = Step.OPEN;
    private boolean analysisOnly;

    private int delay;
    private int waitTicks;
    private int analysisIndex;
    private int buyIndex;
    private int refreshCount;
    private int baseSyncId;
    private long cycleStartNs;
    private long clickNs;
    private long lastAnalysisMs;
    private long lastAfkMs = System.currentTimeMillis();

    private WatchedItem current;
    private WatchedItem pendingItem;
    private long pendingPrice;
    private boolean awaitingResult;
    private int pendingTicks;

    private boolean afkActive;
    private int afkTicks;

    private int sortSlot = -1;
    private int sortClicksLeft;
    private int sortClicksDone;

    public MarketBot(AutoRynekConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ API

    public Mode getMode() {
        return mode;
    }

    public boolean isRunning() {
        return mode != Mode.OFF;
    }

    public void start(boolean analysisOnly) {
        if (cfg.watchlist.isEmpty()) {
            stats().log("Lista pusta - dodaj przedmioty klawiszem L");
            return;
        }
        this.analysisOnly = analysisOnly;
        mode = Mode.ANALYSIS;
        analysisIndex = 0;
        step = Step.OPEN;
        delay = 0;
        waitTicks = 0;
        afkActive = false;
        lastAfkMs = System.currentTimeMillis();
        stats().log(analysisOnly ? "Start: tylko analiza rynku" : "Start: analiza + skupowanie");
    }

    public void stop(String reason) {
        if (mode != Mode.OFF) stats().log("Stop: " + reason);
        mode = Mode.OFF;
        afkActive = false;
        awaitingResult = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) mc.options.jumpKey.setPressed(false);
    }

    /** Reset rynku: ponowne otwarcie /ah search od zera. */
    public void resetMarket() {
        if (mode == Mode.OFF) return;
        step = Step.OPEN;
        delay = 0;
        waitTicks = 0;
        stats().log("Reset rynku (reczny)");
    }

    /** Wywolywane dla kazdej wiadomosci systemowej z serwera. */
    public void onGameMessage(String message) {
        if (!awaitingResult) return;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String kw : cfg.settings.successKeywords) {
            if (!kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                recordPurchase();
                return;
            }
        }
    }

    // ----------------------------------------------------------------- tick

    public void tick(MinecraftClient mc) {
        if (mode == Mode.OFF) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            stop("rozlaczono z serwerem");
            return;
        }
        if (tickAfk(mc)) return;
        if (awaitingResult && ++pendingTicks > 60) {
            awaitingResult = false;
            stats().log("Brak potwierdzenia zakupu");
        }
        if (delay > 0) {
            delay--;
            return;
        }
        if (mode == Mode.ANALYSIS) tickAnalysis(mc);
        else tickBuying(mc);
    }

    // ------------------------------------------------------------- anty-AFK

    /** @return true gdy anty-AFK aktualnie przejmuje sterowanie */
    private boolean tickAfk(MinecraftClient mc) {
        if (!afkActive) {
            long intervalMs = cfg.settings.afkIntervalSeconds * 1000L;
            if (System.currentTimeMillis() - lastAfkMs < intervalMs) return false;
            if (step != Step.OPEN) return false; // czekamy na bezpieczny moment miedzy cyklami
            afkActive = true;
            afkTicks = 0;
            if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
            stats().log("Anty-AFK: wyjscie z rynku + skok");
        }
        afkTicks++;
        if (afkTicks == 6 || afkTicks == 14) mc.options.jumpKey.setPressed(true);
        if (afkTicks == 9 || afkTicks == 17) mc.options.jumpKey.setPressed(false);
        if (afkTicks >= 30) {
            afkActive = false;
            lastAfkMs = System.currentTimeMillis();
            return false;
        }
        return true;
    }

    // --------------------------------------------------- wspolne: otwarcie GUI

    /** Otwiera /ah search, czeka na GUI i sortuje. @return true gdy gotowe do skanowania (step >= SCAN). */
    private boolean prepare(MinecraftClient mc, WatchedItem w) {
        Settings s = cfg.settings;
        switch (step) {
            case OPEN -> {
                baseSyncId = mc.player.currentScreenHandler.syncId;
                cycleStartNs = System.nanoTime();
                if (mode == Mode.BUYING) stats().marketResets++;
                mc.getNetworkHandler().sendChatCommand("ah search " + w.searchQuery);
                waitTicks = 0;
                delay = s.commandCooldownTicks;
                step = Step.WAIT_SCREEN;
            }
            case WAIT_SCREEN -> {
                GenericContainerScreenHandler h = container(mc, false);
                if (h != null && h.syncId != baseSyncId) {
                    waitTicks = s.settleTicks;
                    step = Step.SETTLE;
                } else if (++waitTicks > 100) {
                    stats().log("Brak odpowiedzi na /ah search");
                    waitTicks = 0;
                    delay = 40;
                    step = Step.OPEN;
                }
            }
            case SETTLE -> {
                if (--waitTicks <= 0) {
                    waitTicks = 0;
                    step = s.sortEnabled ? Step.SORT_PLAN : Step.SCAN;
                }
            }
            case SORT_PLAN -> {
                GenericContainerScreenHandler h = container(mc, true);
                if (h == null) {
                    if (++waitTicks > 40) {
                        waitTicks = 0;
                        step = Step.OPEN;
                    }
                } else {
                    sortSlot = findSortSlot(h);
                    if (sortSlot < 0) {
                        if (++waitTicks > 10) {
                            stats().log("Brak przycisku sortowania (" + s.sortItemId + ")");
                            waitTicks = 0;
                            step = Step.SCAN;
                        }
                    } else {
                        sortClicksLeft = clicksNeeded(h.getSlot(sortSlot).getStack());
                        sortClicksDone = 0;
                        waitTicks = 0;
                        step = Step.SORT_CLICK;
                    }
                }
            }
            case SORT_CLICK -> {
                GenericContainerScreenHandler h = container(mc, true);
                if (h == null) {
                    if (++waitTicks > 40) {
                        waitTicks = 0;
                        step = Step.OPEN;
                    }
                } else if (sortClicksLeft > 0) {
                    waitTicks = 0;
                    click(mc, sortSlot, 1); // PRAWY przycisk myszy przelacza tryb sortowania
                    sortClicksLeft--;
                    sortClicksDone++;
                    delay = s.sortClickDelayTicks;
                } else {
                    waitTicks = 0;
                    delay = s.settleTicks + 2;
                    step = Step.SORT_CHECK;
                }
            }
            case SORT_CHECK -> {
                GenericContainerScreenHandler h = container(mc, true);
                if (h != null && sortSlot >= 0 && sortSlot < h.slots.size()) {
                    int sel = selectedSortOption(h.getSlot(sortSlot).getStack());
                    if (sel >= 0) {
                        stats().log("Sortowanie: " + s.sortOptions.get(sel));
                    } else {
                        stats().log("Sortowanie: " + sortClicksDone + "x PPM (nie odczytano trybu)");
                    }
                }
                waitTicks = 0;
                step = Step.SCAN;
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- analiza

    private void tickAnalysis(MinecraftClient mc) {
        if (analysisIndex >= cfg.watchlist.size()) {
            finishAnalysis(mc);
            return;
        }
        WatchedItem w = cfg.watchlist.get(analysisIndex);
        if (!prepare(mc, w)) return;
        if (step != Step.SCAN) return;

        GenericContainerScreenHandler h = container(mc, true);
        if (h == null) {
            if (++waitTicks > 40) {
                waitTicks = 0;
                step = Step.OPEN;
            }
            return;
        }
        waitTicks = 0;

        Average avg = averagePrice(h, w);
        w.analyzed = true;
        if (analysisIndex == 0) dumpMarket(mc, "autorynek-dump-analiza.txt"); // uklad okna po sortowaniu
        if (avg == null) dumpMarket(mc, "autorynek-dump-blad.txt");           // gdy nie udalo sie odczytac cen
        if (avg == null) {
            w.marketPrice = 0;
            w.buyPrice = 0;
            stats().log("Analiza: brak ofert - " + w.label);
        } else {
            w.marketPrice = avg.price();
            w.recalc(cfg.settings.taxMultiplier);
            stats().log("Analiza: " + w.label + " srednia " + avg.price() + " z " + avg.count()
                    + " ofert (" + avg.details() + ") -> skup " + w.buyPrice);
        }
        cfg.save();
        analysisIndex++;
        step = Step.OPEN;
        delay = 10;
    }

    private void finishAnalysis(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        lastAnalysisMs = System.currentTimeMillis();
        stats().log("Analiza zakonczona");
        if (analysisOnly) {
            stop("analiza zakonczona");
            return;
        }
        mode = Mode.BUYING;
        step = Step.OPEN;
        buyIndex = 0;
        delay = 10;
    }

    // -------------------------------------------------------------- kupowanie

    private void tickBuying(MinecraftClient mc) {
        Settings s = cfg.settings;
        if (step == Step.OPEN && System.currentTimeMillis() - lastAnalysisMs > s.reanalysisMinutes * 60_000L) {
            stats().log("Ponowna analiza rynku");
            mode = Mode.ANALYSIS;
            analysisOnly = false;
            analysisIndex = 0;
            return;
        }
        if (step == Step.OPEN) {
            List<WatchedItem> active = cfg.watchlist.stream().filter(w -> w.buyPrice > 0).toList();
            if (active.isEmpty()) {
                stop("brak przedmiotow z cena skupu");
                return;
            }
            current = active.get(Math.floorMod(buyIndex++, active.size()));
            refreshCount = 0;
        }
        if (current == null) {
            step = Step.OPEN;
            return;
        }
        if (!prepare(mc, current)) return;
        switch (step) {
            case SCAN -> scanForPurchase(mc);
            case WAIT_CONFIRM -> waitConfirm(mc);
            default -> {}
        }
    }

    private void scanForPurchase(MinecraftClient mc) {
        Settings s = cfg.settings;
        GenericContainerScreenHandler h = container(mc, true);
        if (h == null) {
            if (++waitTicks > 40) {
                waitTicks = 0;
                step = Step.OPEN;
            }
            return;
        }
        waitTicks = 0;
        stats().lastRefreshMs = (System.nanoTime() - cycleStartNs) / 1_000_000L;

        Offer o = findOffer(h, current, current.buyPrice, true);
        if (o != null) {
            stats().attempts++;
            pendingItem = current;
            pendingPrice = o.price();
            clickNs = System.nanoTime();
            baseSyncId = h.syncId;
            click(mc, o.slot());
            if (s.confirmSlot >= 0) {
                waitTicks = 0;
                step = Step.WAIT_CONFIRM;
            } else {
                beginPending();
                step = Step.OPEN;
                delay = 10;
            }
            return;
        }

        if (s.refreshSlot >= 0 && ++refreshCount < s.refreshesPerItem) {
            cycleStartNs = System.nanoTime();
            click(mc, s.refreshSlot);
            delay = s.actionDelayTicks;
        } else {
            step = Step.OPEN;
        }
    }

    private void waitConfirm(MinecraftClient mc) {
        GenericContainerScreenHandler h = container(mc, false);
        if (h != null && h.syncId != baseSyncId) {
            stats().lastConfirmMs = (System.nanoTime() - clickNs) / 1_000_000L;
            click(mc, cfg.settings.confirmSlot);
            beginPending();
            step = Step.OPEN;
            delay = 10;
        } else if (++waitTicks > cfg.settings.confirmTimeoutTicks) {
            stats().log("Brak okna potwierdzenia");
            waitTicks = 0;
            step = Step.OPEN;
        }
    }

    private void beginPending() {
        pendingTicks = 0;
        if (cfg.settings.successKeywords.isEmpty()) {
            recordPurchase(); // brak slow kluczowych - liczymy zakup od razu po kliknieciu
        } else {
            awaitingResult = true;
        }
    }

    private void recordPurchase() {
        awaitingResult = false;
        if (pendingItem == null) return;
        Stats st = stats();
        st.purchases++;
        st.spent += pendingPrice;
        st.expectedRevenue += Math.round(pendingItem.marketPrice * cfg.settings.taxMultiplier);
        st.history.add(new Stats.HistoryEntry(System.currentTimeMillis(), pendingItem.label, pendingPrice, pendingItem.marketPrice));
        while (st.history.size() > 100) st.history.remove(0);
        st.log("KUPIONO " + pendingItem.label + " za " + pendingPrice);
        cfg.save();
    }

    // --------------------------------------------------------------- helpery

    private Offer findOffer(GenericContainerScreenHandler h, WatchedItem w, long maxPrice, boolean requireMatch) {
        Settings s = cfg.settings;
        int last = Math.min(s.lastListingSlot, h.getRows() * 9 - 1);
        Offer best = null;
        for (int i = Math.max(0, s.firstListingSlot); i <= last; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (requireMatch && !w.key.matches(st)) continue;
            long price = PriceParser.parse(st);
            if (price <= 0 || price > maxPrice) continue;
            if (best == null || price < best.price()) best = new Offer(i, price);
        }
        return best;
    }

    private void click(MinecraftClient mc, int slot) {
        click(mc, slot, 0);
    }

    /** @param button 0 = lewy przycisk myszy, 1 = prawy */
    private void click(MinecraftClient mc, int slot, int button) {
        if (slot < 0) return;
        GenericContainerScreenHandler h = container(mc, false);
        if (h == null || slot >= h.slots.size()) return;
        mc.interactionManager.clickSlot(h.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    // ------------------------------------------------ srednia cena (analiza)

    /**
     * Srednia z cen pierwszych analysisOffers ofert, czytanych od lewego gornego rogu wierszami.
     * Sloty bez ceny (np. szyby-ozdoby) i niepasujace przedmioty sa pomijane.
     * Gdy analysisFixedSlots nie jest puste, brane sa tylko te sloty.
     */
    private Average averagePrice(GenericContainerScreenHandler h, WatchedItem w) {
        Settings s = cfg.settings;
        int max = h.getRows() * 9;
        List<Integer> order = new ArrayList<>();
        boolean fixed = !s.analysisFixedSlots.isEmpty();
        if (fixed) {
            order.addAll(s.analysisFixedSlots);
        } else {
            for (int i = Math.max(0, s.firstListingSlot); i <= Math.min(s.lastListingSlot, max - 1); i++) order.add(i);
        }
        int wanted = fixed ? order.size() : Math.max(1, s.analysisOffers);

        long sum = 0;
        int n = 0;
        StringBuilder details = new StringBuilder();
        for (int slot : order) {
            if (n >= wanted) break;
            if (slot < 0 || slot >= max) continue;
            ItemStack st = h.getSlot(slot).getStack();
            if (st.isEmpty()) continue;
            if (s.analysisRequireMatch && !w.key.matches(st)) continue;
            long price = PriceParser.parse(st);
            if (price <= 0) continue;
            sum += price;
            n++;
            if (details.length() > 0) details.append(", ");
            details.append(price);
        }
        if (n == 0) return null;
        return new Average(Math.round((double) sum / n), n, details.toString());
    }

    /** Zapisuje zrzut aktualnego okna do config/<fileName> (do diagnozy ukladu rynku). */
    private void dumpMarket(MinecraftClient mc, String fileName) {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
        try {
            ScreenHooks.writeDump(hs, FabricLoader.getInstance().getConfigDir().resolve(fileName));
            stats().log("Zapisano zrzut: " + fileName);
        } catch (IOException | RuntimeException e) {
            stats().log("Nie zapisano zrzutu: " + e.getMessage());
        }
    }

    // --------------------------------------------------------- sortowanie

    /** Szuka przycisku sortowania (domyslnie repeater) w dolnym rzedzie okna. */
    private int findSortSlot(GenericContainerScreenHandler h) {
        Settings s = cfg.settings;
        int max = h.getRows() * 9;
        if (s.sortButtonSlot >= 0) return s.sortButtonSlot < max ? s.sortButtonSlot : -1;
        for (int i = max - 1; i >= Math.max(0, max - 9); i--) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty() && Registries.ITEM.getId(st.getItem()).toString().equals(s.sortItemId)) return i;
        }
        return -1;
    }

    /** Ile razy trzeba kliknac PRAWYM, zeby z aktualnego trybu dojsc do sortTargetText. */
    private int clicksNeeded(ItemStack button) {
        Settings s = cfg.settings;
        int target = -1;
        for (int i = 0; i < s.sortOptions.size(); i++) {
            if (s.sortOptions.get(i).equalsIgnoreCase(s.sortTargetText)) target = i;
        }
        int selected = selectedSortOption(button);
        if (target >= 0 && selected >= 0) {
            return Math.floorMod(target - selected, s.sortOptions.size());
        }
        return s.sortClicks; // nie wiadomo, jaki tryb jest ustawiony - klikamy tyle razy, ile w configu (domyslnie 2)
    }

    /** Odczytuje z opisu przycisku zaznaczony (podkreslony) tryb sortowania. @return indeks w sortOptions lub -1 */
    private int selectedSortOption(ItemStack button) {
        LoreComponent lore = button.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        List<String> options = cfg.settings.sortOptions;
        int found = -1;
        for (Text line : lore.lines()) {
            String text = line.getString().toLowerCase(Locale.ROOT);
            for (int i = 0; i < options.size(); i++) {
                if (text.contains(options.get(i).toLowerCase(Locale.ROOT)) && isUnderlined(line)) {
                    if (found >= 0 && found != i) return -1; // niejednoznaczne
                    found = i;
                }
            }
        }
        return found;
    }

    public static boolean isUnderlined(Text text) {
        if (text.getStyle().isUnderlined()) return true;
        for (Text sibling : text.getSiblings()) {
            if (isUnderlined(sibling)) return true;
        }
        return false;
    }

    /** @param market true = dodatkowo wymagaj, aby tytul okna zawieral marketTitleKeyword */
    private GenericContainerScreenHandler container(MinecraftClient mc, boolean market) {
        if (!(mc.currentScreen instanceof GenericContainerScreen scr)) return null;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return null;
        String kw = cfg.settings.marketTitleKeyword;
        if (market && kw != null && !kw.isBlank()
                && !scr.getTitle().getString().toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return h;
    }

    private Stats stats() {
        return cfg.stats;
    }
}
