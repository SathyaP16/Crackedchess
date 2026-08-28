package engine;
import java.util.Map;

public class Economy {
    private static final Map<Piece.Type, Integer> COIN_VALUES = Map.of(
        Piece.Type.PAWN,   1,
        Piece.Type.KNIGHT, 2,
        Piece.Type.BISHOP, 2,
        Piece.Type.ROOK,   3,
        Piece.Type.QUEEN,  4,
        Piece.Type.KING,   0
    );

    public static final int TRAP_COST = 3;
    public static final int CROWN_TRANSFER_COST = 5;

    private Economy() {}

    public static int awardForCapture(Piece capturedPiece, Player capturingPlayer) {
        int coins = COIN_VALUES.getOrDefault(capturedPiece.getType(), 0);
        capturingPlayer.addCoins(coins);
        return coins;
    }

    public static boolean chargeTrapCost(Player player) {
        return player.spendCoins(TRAP_COST);
    }

    public static boolean chargeCrownTransferCost(Player player) {
        return player.spendCoins(CROWN_TRANSFER_COST);
    }

    // ── NEW: single source of truth for affordability ───────────────────────
    /**
     * CONCEPT: Single Source of Truth
     * Before this, "can I afford a crown transfer?" was answered in TWO
     * places — RulesEngine compared getCoins() against the raw constant, and
     * Economy separately did the actual charging. If CROWN_TRANSFER_COST's
     * meaning ever got more complex (e.g. "costs more after turn 30"), only
     * one of those two spots would need to change, and it's easy to update
     * one and forget the other. Now Economy owns BOTH the check and the charge.
     */
    public static boolean canAffordTrap(Player player) {
        return player.getCoins() >= TRAP_COST;
    }

    public static boolean canAffordCrownTransfer(Player player) {
        return player.getCoins() >= CROWN_TRANSFER_COST;
    }

    public static int getValueOf(Piece.Type type) {
        return COIN_VALUES.getOrDefault(type, 0);
    }
}