package inceris.rcc.fabric;

import inceris.rcc.common.CurrencyAction;
import net.impactdev.impactor.api.economy.EconomyService;
import net.impactdev.impactor.api.economy.transactions.details.EconomyResultType;
import net.kyori.adventure.key.Key;
import java.util.UUID;
import java.util.concurrent.*;

/** Impactor uses UUID accounts and asynchronous storage for online and offline players alike. */
final class ImpactorCurrency {
    private ImpactorCurrency() {}
    static CompletionStage<CurrencyAction.Result> apply(Executor executor, CurrencyAction.Operation operation, UUID player) {
        var result = new CompletableFuture<CurrencyAction.Result>();
        executor.execute(() -> {
            try {
                apply(EconomyService.instance(), executor, operation, player).whenComplete((value, error) -> {
                    if (error != null) result.completeExceptionally(error); else result.complete(value);
                });
            } catch (RuntimeException | LinkageError e) { result.completeExceptionally(e); }
        });
        return result;
    }
    static CompletionStage<CurrencyAction.Result> apply(EconomyService economy, Executor executor, CurrencyAction.Operation operation, UUID player) {
        var result = new CompletableFuture<CurrencyAction.Result>();
        try {
            var currency = economy.currencies().currency(Key.key(operation.currency())).orElseThrow(() -> new IllegalArgumentException("Unknown Impactor currency: " + operation.currency()));
            economy.account(currency, player).whenComplete((account, loadError) -> executor.execute(() -> {
                    if (loadError != null) { result.completeExceptionally(loadError); return; }
                    try {
                        var transaction = switch (operation.action()) {
                            case ADD -> account.deposit(operation.amount());
                            case REMOVE -> account.withdraw(operation.amount());
                            case SET -> account.set(operation.amount());
                        };
                        if (transaction.result() != EconomyResultType.SUCCESS) result.complete(CurrencyAction.Result.failure("Impactor: " + transaction.result()));
                        else economy.save(account).whenComplete((unused, saveError) -> {
                            if (saveError != null) result.completeExceptionally(saveError);
                            else result.complete(CurrencyAction.Result.ok());
                        });
                    } catch (RuntimeException | LinkageError e) { result.completeExceptionally(e); }
            }));
        } catch (RuntimeException | LinkageError e) { result.completeExceptionally(e); }
        return result;
    }
}
