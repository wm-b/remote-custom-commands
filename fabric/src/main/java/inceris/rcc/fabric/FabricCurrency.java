package inceris.rcc.fabric;

import inceris.rcc.common.CurrencyAction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.scoreboard.ScoreHolder;
import java.util.*;
import java.util.concurrent.*;

final class FabricCurrency {
    private FabricCurrency() {}
    static CompletionStage<CurrencyAction.Result> apply(MinecraftServer server, CurrencyAction.Operation operation) {
        if (operation.type() == CurrencyAction.Type.VAULT) return CompletableFuture.completedFuture(CurrencyAction.Result.failure("Vault requires a Paper destination"));
        if (operation.type() == CurrencyAction.Type.IMPACTOR && !FabricLoader.getInstance().isModLoaded("impactor")) return CompletableFuture.completedFuture(CurrencyAction.Result.failure("Impactor is not installed"));
        if (operation.type() == CurrencyAction.Type.SCOREBOARD) {
            String name = !operation.usesUuid() ? operation.player() : operation.nameHint();
            if (name.isEmpty() && server.getUserCache() != null) name = server.getUserCache().getByUuid(UUID.fromString(operation.player())).map(p -> p.getName()).orElse("");
            if (!name.isEmpty()) return CompletableFuture.completedFuture(scoreboard(server, operation, name));
            // A player need not have visited this backend: resolve uncached online-mode UUIDs asynchronously.
            return CompletableFuture.supplyAsync(() -> {
                var profile = server.getSessionService().fetchProfile(UUID.fromString(operation.player()), false);
                if (profile == null) throw new IllegalArgumentException("Unknown scoreboard UUID; use currency.player with a name");
                return profile.profile().getName();
            }).thenCompose(resolved -> {
                var result = new CompletableFuture<CurrencyAction.Result>();
                server.execute(() -> result.complete(scoreboard(server, operation, resolved)));
                return result;
            });
        }
        CompletableFuture<UUID> uuid = operation.usesUuid() ? CompletableFuture.completedFuture(UUID.fromString(operation.player()))
            : CompletableFuture.supplyAsync(() -> {
                if (server.getUserCache() == null) throw new IllegalArgumentException("Player profile cache unavailable; use a UUID");
                return server.getUserCache().findByName(operation.player()).orElseThrow(() -> new IllegalArgumentException("Unknown player; use a UUID")).getId();
            });
        return uuid.thenCompose(id -> ImpactorCurrency.apply(server::execute, operation, id));
    }
    private static CurrencyAction.Result scoreboard(MinecraftServer server, CurrencyAction.Operation operation, String name) {
        try {
            var scoreboard = server.getScoreboard();
            var objective = scoreboard.getNullableObjective(operation.currency());
            if (objective == null) throw new IllegalArgumentException("Unknown scoreboard objective: " + operation.currency());
            if (objective.getCriterion().isReadOnly()) throw new IllegalArgumentException("Scoreboard objective is read only");
            var holder = ScoreHolder.fromName(name);
            var existing = scoreboard.getScore(holder, objective);
            int updated = operation.scoreAfter(existing == null ? 0 : existing.getScore());
            scoreboard.getOrCreateScore(holder, objective).setScore(updated);
            return CurrencyAction.Result.ok();
        } catch (RuntimeException e) { return CurrencyAction.Result.failure("Currency failed: " + e.getMessage()); }
    }
}
