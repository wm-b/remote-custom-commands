package inceris.rcc.common;

import java.math.BigDecimal;
import java.util.*;

/** Templates are resolved at the origin; operations are validated again at the destination. */
public record CurrencyAction(Type type, Action action, String currency, String amount, String player) {
    public enum Type { IMPACTOR, VAULT, SCOREBOARD }
    public enum Action { ADD, REMOVE, SET }
    public record Result(boolean success, String detail) {
        public static Result ok() { return new Result(true, "OK"); }
        public static Result failure(String detail) { return new Result(false, detail); }
    }
    public record Operation(Type type, Action action, String currency, BigDecimal amount, String player, String nameHint) {
        public Operation {
            Objects.requireNonNull(type); Objects.requireNonNull(action); Objects.requireNonNull(amount);
            if (currency == null || currency.length() > 256 || (type != Type.VAULT && currency.isBlank())) throw new IllegalArgumentException("Missing or invalid currency");
            if (amount.signum() < 0 || amount.precision() > 32 || Math.abs((long) amount.scale()) > 16) throw new IllegalArgumentException("Currency amount must be a finite nonnegative number of at most 32 digits");
            if (type == Type.SCOREBOARD) {
                try { amount.intValueExact(); }
                catch (ArithmeticException e) { throw new IllegalArgumentException("Scoreboard amount must be a whole number within the integer range", e); }
            }
            if (player == null || !(player.matches("[a-zA-Z0-9_]{1,16}") || player.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))) throw new IllegalArgumentException("Currency player must be a name or UUID");
            if (nameHint == null || !nameHint.matches("[a-zA-Z0-9_]{1,16}")) nameHint = "";
        }
        public boolean usesUuid() { return player.length() == 36; }
        public int scoreAfter(int current) {
            int value = amount.intValueExact();
            return switch (action) {
                case ADD -> Math.addExact(current, value);
                case REMOVE -> { if (current < value) throw new IllegalArgumentException("Insufficient scoreboard balance"); yield Math.subtractExact(current, value); }
                case SET -> value;
            };
        }
    }
    public Operation resolve(String name, String uuid, List<String> args, String origin, String target) {
        String identifier = Placeholders.resolve(player, name, uuid, args, origin, target);
        String hint = identifier.equals(uuid) && name != null ? name : "";
        return new Operation(type, action, Placeholders.resolve(currency, name, uuid, args, origin, target),
            new BigDecimal(Placeholders.resolve(amount, name, uuid, args, origin, target)), identifier, hint);
    }
}
