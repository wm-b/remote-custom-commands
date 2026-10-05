package inceris.rcc.fabric;

import inceris.rcc.common.CurrencyAction;
import net.impactdev.impactor.api.economy.EconomyService;
import net.impactdev.impactor.api.economy.accounts.Account;
import net.impactdev.impactor.api.economy.currency.Currency;
import net.impactdev.impactor.api.economy.currency.CurrencyProvider;
import net.impactdev.impactor.api.economy.transactions.EconomyTransaction;
import net.impactdev.impactor.api.economy.transactions.details.EconomyResultType;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ImpactorCurrencyTest {
    @Test void offlineUuidAccountWaitsForLoadingAndSavingForEveryAction() {
        for (CurrencyAction.Action action : CurrencyAction.Action.values()) {
            var provider = new Provider();
            var result = provider.apply(action).toCompletableFuture();
            assertFalse(result.isDone());
            assertTrue(provider.mutations.isEmpty());
            provider.loaded.complete(provider.account);
            assertEquals(List.of(switch (action) { case ADD -> "deposit"; case REMOVE -> "withdraw"; case SET -> "set"; }), provider.mutations);
            assertFalse(result.isDone());
            assertEquals(1, provider.saves);
            provider.saved.complete(null);
            assertTrue(result.join().success());
        }
    }
    @Test void refusedTransactionDoesNotSaveOrSucceed() {
        var provider = new Provider();
        provider.transactionResult = Arrays.stream(EconomyResultType.values()).filter(r -> r != EconomyResultType.SUCCESS).findFirst().orElseThrow();
        var result = provider.apply(CurrencyAction.Action.REMOVE).toCompletableFuture();
        provider.loaded.complete(provider.account);
        assertFalse(result.join().success());
        assertEquals(0, provider.saves);
    }
    @Test void storageFailuresPropagateAndMissingCurrencyDoesNotFetchAnAccount() {
        var loading = new Provider();
        var result = loading.apply(CurrencyAction.Action.ADD).toCompletableFuture();
        loading.loaded.completeExceptionally(new IllegalStateException("Load failed"));
        assertThrows(CompletionException.class, result::join);
        assertTrue(loading.mutations.isEmpty());
        var saving = new Provider();
        result = saving.apply(CurrencyAction.Action.ADD).toCompletableFuture();
        saving.loaded.complete(saving.account);
        saving.saved.completeExceptionally(new IllegalStateException("Save failed"));
        assertThrows(CompletionException.class, result::join);
        var missing = new Provider(); missing.hasCurrency = false;
        assertThrows(CompletionException.class, () -> missing.apply(CurrencyAction.Action.ADD).toCompletableFuture().join());
        assertEquals(0, missing.loads);
    }
    private static class Provider {
        final UUID player = UUID.randomUUID();
        final CompletableFuture<Account> loaded = new CompletableFuture<>();
        final CompletableFuture<Void> saved = new CompletableFuture<>();
        final List<String> mutations = new ArrayList<>();
        EconomyResultType transactionResult = EconomyResultType.SUCCESS;
        boolean hasCurrency = true; int loads, saves;
        final Currency currency = proxy(Currency.class, (method, args) -> { throw new AssertionError(method); });
        final Account account = proxy(Account.class, (method, args) -> {
            assertTrue(List.of("deposit", "withdraw", "set").contains(method));
            assertEquals(new BigDecimal("12.5"), args[0]);
            mutations.add(method);
            return proxy(EconomyTransaction.class, (m, a) -> {
                assertEquals("result", m); return transactionResult;
            });
        });
        final CurrencyProvider currencies = proxy(CurrencyProvider.class, (method, args) -> {
            assertEquals("currency", method); assertEquals(Key.key("impactor:event_points"), args[0]);
            return hasCurrency ? Optional.of(currency) : Optional.empty();
        });
        final EconomyService economy = proxy(EconomyService.class, (method, args) -> switch (method) {
            case "currencies" -> currencies;
            case "account" -> {
                assertSame(currency, args[0]); assertEquals(player, args[1]); loads++; yield loaded;
            }
            case "save" -> { assertSame(account, args[0]); saves++; yield saved; }
            default -> throw new AssertionError(method);
        });
        CompletionStage<CurrencyAction.Result> apply(CurrencyAction.Action action) {
            return ImpactorCurrency.apply(economy, Runnable::run, new CurrencyAction.Operation(CurrencyAction.Type.IMPACTOR, action,
                "impactor:event_points", new BigDecimal("12.5"), player.toString(), ""), player);
        }
    }
    private interface Invocation { Object invoke(String method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }
}
