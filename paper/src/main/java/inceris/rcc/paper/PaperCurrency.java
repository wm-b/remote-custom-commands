package inceris.rcc.paper;

import inceris.rcc.common.CurrencyAction;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

final class PaperCurrency {
    private PaperCurrency() {}
    static CompletionStage<CurrencyAction.Result> apply(JavaPlugin plugin, CurrencyAction.Operation operation) {
        if (operation.type() == CurrencyAction.Type.IMPACTOR) return CompletableFuture.completedFuture(CurrencyAction.Result.failure("Impactor requires a Fabric destination"));
        if (operation.type() == CurrencyAction.Type.SCOREBOARD) {
            String name = operation.usesUuid() ? Bukkit.getOfflinePlayer(UUID.fromString(operation.player())).getName() : operation.player();
            if (name == null && !operation.nameHint().isEmpty()) name = operation.nameHint();
            CompletableFuture<String> resolved = name != null ? CompletableFuture.completedFuture(name)
                : Bukkit.createPlayerProfile(UUID.fromString(operation.player())).update().thenApply(profile -> profile.getName());
            return resolved.thenCompose(identifier -> onServer(plugin, () -> scoreboard(operation, identifier)));
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return CompletableFuture.completedFuture(CurrencyAction.Result.failure("Vault is not installed or enabled"));
        // UUID account lookup never depends on the recipient being connected. Name lookup may contact the profile service.
        CompletableFuture<OfflinePlayer> player = operation.usesUuid()
            ? CompletableFuture.completedFuture(Bukkit.getOfflinePlayer(UUID.fromString(operation.player())))
            : CompletableFuture.supplyAsync(() -> Bukkit.getOfflinePlayer(operation.player()));
        return player.thenCompose(account -> onServer(plugin, () -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return CurrencyAction.Result.failure("Vault is not installed or enabled");
            return VaultCurrency.apply(account, operation);
        }));
    }
    private static CompletionStage<CurrencyAction.Result> onServer(JavaPlugin plugin, java.util.function.Supplier<CurrencyAction.Result> action) {
        var result = new CompletableFuture<CurrencyAction.Result>();
        Runnable task = () -> {
            try { result.complete(action.get()); }
            catch (RuntimeException | LinkageError e) { result.complete(CurrencyAction.Result.failure("Currency failed: " + e.getMessage())); }
        };
        if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task);
        return result;
    }
    private static CurrencyAction.Result scoreboard(CurrencyAction.Operation operation, String name) {
        if (name == null) throw new IllegalArgumentException("Unknown scoreboard UUID; use currency.player with a name");
        var objective = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(operation.currency());
        if (objective == null) throw new IllegalArgumentException("Unknown scoreboard objective: " + operation.currency());
        if (objective.getTrackedCriteria().isReadOnly()) throw new IllegalArgumentException("Scoreboard objective is read only");
        var score = objective.getScore(name);
        score.setScore(operation.scoreAfter(score.getScore()));
        return CurrencyAction.Result.ok();
    }
}
