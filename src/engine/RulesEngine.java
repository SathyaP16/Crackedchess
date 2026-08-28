package engine;

import shared.Action;
import shared.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * CONCEPT: Pure Functions and Separation of Concerns
 * RulesEngine stores NO state — every method takes a Board and returns a result.
 * It never modifies anything. These are PURE FUNCTIONS.
 *
 * OPTIMIZATION NOTE (added):
 * The previous version had one real hot-path problem: validating a SINGLE
 * submitted move (isLegalAction → isLegalMove) called filterLegalMoves(),
 * which simulates EVERY pseudo-legal move the piece has, just to check
 * whether one specific target survives. If a queen has 20 legal-looking
 * moves and the player only tried 1 of them, we were paying for all 20
 * "clone the board + deep-copy every piece + scan the grid" simulations
 * to answer a yes/no question about ONE move.
 *
 * FIX: we extracted the core "would this one move leave me in check?" check
 * into wouldLeaveCrownInCheck(). isLegalMove() now calls it for exactly the
 * one target the player submitted. filterLegalMoves() (used by the GUI to
 * highlight ALL legal destinations) still needs to check every candidate —
 * that's an inherent cost of "show me everything you can do," not a bug.
 *
 * No public method signatures changed, so Game.java, GameController.java,
 * and everything else that calls into RulesEngine keeps working unmodified.
 */
public class RulesEngine {

    private RulesEngine() {}

    // ── Check Detection ───────────────────────────────────────────────────────

    /**
     * Returns true if the given player's crown holder is currently in check.
     * Uses pseudo-legal opponent moves to avoid infinite recursion.
     *
     * PERFORMANCE NOTE: board.getPiecesFor(opponentId) does a full 8×8 grid
     * scan internally (see Board.getAllPieces()). This method is on the hot
     * path — it's called once per candidate move during legal-move filtering.
     * We can't fix that scan from inside RulesEngine (it's Board's internal
     * data structure), but we DO make sure we never call this method more
     * times than necessary — see wouldLeaveCrownInCheck() below.
     */
    public static boolean isInCheck(Board board, int playerId, Player player) {
        Position crownPos = player.getCrownPosition();
        if (crownPos == null) return false;

        int opponentId = 1 - playerId;
        for (Piece opp : board.getPiecesFor(opponentId)) {
            // Short-circuit: contains() walks the returned list, but the list
            // itself is usually small (≤16 moves per piece), so this part
            // was never the expensive bit — the grid scan to BUILD the piece
            // list is. Flagging this for a future Board-level cache (see notes).
            if (opp.getValidMoves(board).contains(crownPos)) return true;
        }
        return false;
    }

    // ── Core Simulation Helper (NEW) ──────────────────────────────────────────

    /**
     * CONCEPT: Extract-and-Reuse
     * This is the single piece of logic both filterLegalMoves() and the fast
     * single-move validator (isLegalMove) actually need: "if this one piece
     * moved to this one target, would MY crown holder end up in check?"
     *
     * Previously this logic was inlined inside filterLegalMoves()'s loop,
     * which meant there was no way to ask the question about just ONE move
     * without looping over all of them. Pulling it out means isLegalMove()
     * can now pay the simulation cost exactly once instead of once-per-
     * pseudo-legal-move.
     *
     * @param board  The real (unmutated) board.
     * @param piece  The piece being hypothetically moved.
     * @param target The square it would move to.
     * @param player The moving player (for crown position bookkeeping).
     * @return true if this specific move would leave the player's crown in check.
     */
    private static boolean wouldLeaveCrownInCheck(Board board, Piece piece,
                                                   Position target, Player player) {
        // Clone-and-simulate: still the correctness-safe approach (see the
        // undo-based alternative discussed separately if you want to go
        // further later — that trades some risk of state-corruption bugs
        // for speed, so it's worth keeping this version as a test oracle
        // even if you eventually add the faster path).
        Board simulated = new Board(board);
        simulated.applyMove(piece.getPosition(), target);

        Player simulatedPlayer = new Player(player.getId(), player.getName());
        simulatedPlayer.setCrownPosition(player.getCrownPosition());
        if (piece.isCrownHolder()) {
            simulatedPlayer.setCrownPosition(target);
        }

        return isInCheck(simulated, player.getId(), simulatedPlayer);
    }

    // ── Legal Move Filtering (for GUI move-highlighting) ──────────────────────

    /**
     * Filters a piece's raw moves to only those that don't leave the
     * crown holder in check (the King Safety Filter).
     *
     * Used by GameController.getLegalMovesFor() to highlight every valid
     * destination square — this genuinely needs to check every candidate,
     * so its cost is inherent to what it's answering, not a bug to fix.
     */
    public static List<Position> filterLegalMoves(Board board, Piece piece, Player player) {
        List<Position> pseudoLegal = piece.getValidMoves(board);
        List<Position> legal       = new ArrayList<>();

        for (Position target : pseudoLegal) {
            if (!wouldLeaveCrownInCheck(board, piece, target, player)) {
                legal.add(target);
            }
        }

        return legal;
    }

    // ── Checkmate Detection ───────────────────────────────────────────────────

    /**
     * Returns true if the given player is in CHECKMATE:
     * in check AND no legal move escapes it.
     */
    public static boolean isCheckmate(Board board, int playerId, Player player) {
        if (!isInCheck(board, playerId, player)) return false;
        return hasNoLegalMoves(board, playerId, player);
    }

    private static boolean hasNoLegalMoves(Board board, int playerId, Player player) {
        for (Piece piece : board.getPiecesFor(playerId)) {
            if (!filterLegalMoves(board, piece, player).isEmpty()) return false;
        }
        return true;
    }

    // ── Action Validation ─────────────────────────────────────────────────────

    /**
     * The single validation gate — every action passes through here before
     * being applied to real game state.
     */
    public static boolean isLegalAction(Action action, Board board,
                                         Player[] players, int totalTurns) {
        Player actor = players[action.playerId];
        return switch (action.type) {
            case MOVE           -> isLegalMove(action, board, actor);
            case PLACE_TRAP     -> isLegalTrapPlacement(action, board, actor, totalTurns);
            case CROWN_TRANSFER -> isLegalCrownTransfer(action, board, actor, players);
            default             -> false;
        };
    }

    // ── Private Validation Helpers ────────────────────────────────────────────

    /**
     * OPTIMIZED: previously called filterLegalMoves(board, piece, actor)
     * .contains(action.to) — which simulated EVERY pseudo-legal move of the
     * piece just to answer a question about one specific target square.
     *
     * Now: first do the CHEAP check (is action.to even physically reachable?
     * O(list scan), no board cloning). Only if that passes do we pay for
     * exactly ONE simulation, for exactly the move being validated.
     */
    private static boolean isLegalMove(Action action, Board board, Player actor) {
        Piece piece = board.getPieceAt(action.from);
        if (piece == null || piece.getOwnerId() != actor.getId()) return false;

        List<Position> pseudoLegal = piece.getValidMoves(board);
        if (!pseudoLegal.contains(action.to)) return false; // cheap rejection first

        return !wouldLeaveCrownInCheck(board, piece, action.to, actor);
    }

    private static boolean isLegalTrapPlacement(Action action, Board board,
                                                  Player actor, int totalTurns) {
        if (actor.getCoins() < Economy.TRAP_COST)   return false;
        if (!actor.canPlaceTrap())                   return false;
        if (board.getPieceAt(action.to) != null)     return false;
        if (board.getTrapAt(action.to)  != null)     return false;
        return isInPermittedTerritory(action.to, actor.getId(), totalTurns);
    }

    private static boolean isLegalCrownTransfer(Action action, Board board,
                                                  Player actor, Player[] players) {
        if (actor.hasCrownTransferUsed()) return false;
        if (isInCheck(board, actor.getId(), actor)) return false;

        // See Economy.java changes below — this now delegates the
        // affordability check to Economy instead of comparing against the
        // raw constant here too, so there's exactly one place that decides
        // "can this player afford a crown transfer."
        if (!Economy.canAffordCrownTransfer(actor)) return false;

        Piece fromPiece = board.getPieceAt(action.from);
        if (fromPiece == null || !fromPiece.isCrownHolder())     return false;
        if (fromPiece.getOwnerId() != actor.getId())             return false;

        Piece toPiece = board.getPieceAt(action.to);
        if (toPiece == null)                              return false;
        if (toPiece.getOwnerId() != actor.getId())        return false;
        if (toPiece.getType() == Piece.Type.PAWN)         return false;
        if (toPiece.isCrownHolder())                      return false;

        return true;
    }

    /**
     * Returns true if the given position is within the player's allowed trap
     * placement zone, which shrinks every 15 total turns.
     */
    private static boolean isInPermittedTerritory(Position pos, int playerId, int totalTurns) {
        // Math.min cap changed from 3 to 2 (Stops shrinking at Phase 2)
        int phase       = Math.min(totalTurns / 15, 2); 
        int rowsAllowed = 4 - phase;

        if (playerId == 0) {
            // White: Anchored to Center Row 4. 
            // Phase 0: Rows 4-7 | Phase 1: Rows 4-6 | Phase 2: Rows 4-5
            return pos.row >= 4 && pos.row <= 4 + (rowsAllowed - 1);
        } else {
            // Black: Anchored to Center Row 3. 
            // Phase 0: Rows 0-3 | Phase 1: Rows 1-3 | Phase 2: Rows 2-3
            return pos.row >= 3 - (rowsAllowed - 1) && pos.row <= 3;
        }
    }
}