package at.esoren.vystormpaddock;

import java.util.*;

/**
 * Race betting: while a rider waits in a course's start circle, others bet whether the rider will beat their own best
 * time on that course ({@code BEAT}) or not ({@code MISS}; no best yet = finishing counts as beating). Leaving the circle
 * starts the run and closes the book. Payout is pari-mutuel: the whole pool, minus the house cut, goes to the winning
 * side in proportion to the stakes. If one side is empty, everybody gets their stake back. In memory only; the plugin
 * refunds open books on shutdown. Pure data, server thread.
 */
final class RaceBets {
    enum Side { BEAT, MISS }

    record Bet(UUID bettor, String name, Side side, double amount) {}

    static final class Book {
        final UUID rider; final String riderName; final String course;
        final List<Bet> bets = new ArrayList<>();
        boolean locked;
        /** Best time to beat in millis; -1 = finishing is enough. */
        long target = -1;

        Book(UUID rider, String riderName, String course) { this.rider = rider; this.riderName = riderName; this.course = course; }

        double pool() { return bets.stream().mapToDouble(Bet::amount).sum(); }
        double pool(Side side) { return bets.stream().filter(b -> b.side() == side).mapToDouble(Bet::amount).sum(); }

        /** Current payout per unit stake for {@code side} (pool after cut / side pool), or 0 without stakes on it. */
        double odds(Side side, double cut) { double s = pool(side); return s <= 0 ? 0 : pool() * (1 - cut) / s; }
    }

    private final Map<UUID, Book> books = new HashMap<>();

    Book book(UUID rider) { return books.get(rider); }
    Collection<Book> books() { return List.copyOf(books.values()); }

    /** Opens (or returns) the rider's book for {@code course}; a book for another course is replaced only when empty. */
    Book open(UUID rider, String riderName, String course) {
        Book book = books.get(rider);
        if (book != null && (book.locked || !book.course.equals(course)) && book.bets.isEmpty()) book = null;
        if (book == null) { book = new Book(rider, riderName, course); books.put(rider, book); }
        return book;
    }

    /** Result codes: {@code ok}, {@code closed}, {@code self}, {@code twice}, {@code amount}, {@code full}. */
    String check(UUID rider, UUID bettor, double amount, double min, double max, int maxBets) {
        Book book = books.get(rider);
        if (book == null || book.locked) return "closed";
        if (rider.equals(bettor)) return "self";
        if (book.bets.stream().anyMatch(b -> b.bettor().equals(bettor))) return "twice";
        if (!Double.isFinite(amount) || amount < min || amount > max) return "amount";
        if (book.bets.size() >= maxBets) return "full";
        return "ok";
    }

    void place(UUID rider, Bet bet) { books.get(rider).bets.add(bet); }

    /** Closes the book when the run starts; null if there was none. */
    Book lock(UUID rider, long target) {
        Book book = books.get(rider);
        if (book == null) return null;
        if (book.bets.isEmpty()) { books.remove(rider); return null; }
        book.locked = true; book.target = target;
        return book;
    }

    Book remove(UUID rider) { return books.remove(rider); }

    /** Did the run win for BEAT? {@code millis < 0} = no finish. */
    static Side outcome(long target, long millis) {
        if (millis < 0) return Side.MISS;
        return target < 0 || millis < target ? Side.BEAT : Side.MISS;
    }

    /**
     * Payout per bettor for {@code winner}. Stakes come back without a cut when nobody bet on one of the sides; amounts
     * are rounded down to cents, so the house never pays more than the pool.
     */
    static Map<UUID, Double> payouts(List<Bet> bets, Side winner, double cut) {
        Map<UUID, Double> out = new LinkedHashMap<>();
        double pool = 0, winning = 0, losing = 0;
        for (Bet b : bets) { pool += b.amount(); if (b.side() == winner) winning += b.amount(); else losing += b.amount(); }
        if (winning <= 0 || losing <= 0) { for (Bet b : bets) out.merge(b.bettor(), b.amount(), Double::sum); return out; }
        double share = pool * (1 - Math.clamp(cut, 0, 0.5));
        for (Bet b : bets) if (b.side() == winner) out.merge(b.bettor(), Math.floor(share * b.amount() / winning * 100) / 100, Double::sum);
        return out;
    }

    /**
     * Why a winning bet looks arranged, or empty: {@code party} = a winning MISS bet by someone in the rider's party,
     * {@code abort} = a MISS bet that won because the run was cancelled (rider got off, timed out, left). Only logged;
     * admins decide what to do about it.
     */
    static String suspicion(Bet bet, Side winner, long millis, boolean sameParty) {
        if (bet.side() != Side.MISS || winner != Side.MISS) return "";
        if (sameParty) return "party";
        return millis < 0 ? "abort" : "";
    }

    /** One CSV line for the bet log (texts quoted). */
    static String logLine(long at, Book book, long millis, Side winner, Bet bet, double payout, String flag) {
        java.util.function.Function<String, String> q = s -> '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"';
        return String.join(",", java.time.Instant.ofEpochMilli(at).toString(), q.apply(book.course), q.apply(book.riderName), Long.toString(book.target),
                Long.toString(millis), winner.name(), q.apply(bet.name()), bet.side().name(), String.format(java.util.Locale.ROOT, "%.2f", bet.amount()),
                String.format(java.util.Locale.ROOT, "%.2f", payout), flag);
    }

    /** Stakes back for everybody (cancelled book). */
    static Map<UUID, Double> refunds(List<Bet> bets) {
        Map<UUID, Double> out = new LinkedHashMap<>();
        for (Bet b : bets) out.merge(b.bettor(), b.amount(), Double::sum);
        return out;
    }
}
