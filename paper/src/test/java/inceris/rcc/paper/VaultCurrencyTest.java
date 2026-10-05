package inceris.rcc.paper;

import inceris.rcc.common.CurrencyAction;
import net.milkbowl.vault.economy.*;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VaultCurrencyTest {
    @Test void offlineAccountSupportsAddRemoveAndSet() {
        var provider = new Provider(10);
        assertTrue(provider.apply(CurrencyAction.Action.ADD, "2.50").success());
        assertEquals(12.5, provider.balance);
        assertTrue(provider.apply(CurrencyAction.Action.REMOVE, "1.50").success());
        assertEquals(11, provider.balance);
        assertTrue(provider.apply(CurrencyAction.Action.SET, "5").success());
        assertEquals(5, provider.balance);
        assertTrue(provider.apply(CurrencyAction.Action.SET, "20").success());
        assertEquals(20, provider.balance);
        assertTrue(provider.apply(CurrencyAction.Action.SET, "20").success());
        assertEquals(4, provider.transactions);
    }
    @Test void failedWithdrawalAndProviderFailureDoNotReportSuccess() {
        var provider = new Provider(2);
        assertFalse(provider.apply(CurrencyAction.Action.REMOVE, "3").success());
        assertEquals(0, provider.transactions);
        provider.failure = true;
        assertFalse(provider.apply(CurrencyAction.Action.ADD, "3").success());
        assertEquals(2, provider.balance);
    }
    @Test void accountCreationAndProviderPrecisionAreChecked() {
        var provider = new Provider(0);
        provider.account = false;
        assertTrue(provider.apply(CurrencyAction.Action.ADD, "1").success());
        assertEquals(1, provider.creations);
        assertFalse(provider.apply(CurrencyAction.Action.ADD, "1.001").success());
        assertEquals(1, provider.transactions);
        provider.account = false; provider.failure = true;
        assertFalse(provider.apply(CurrencyAction.Action.ADD, "1").success());
    }
    @Test void setUsesDecimalDifferenceAndRejectsUnrepresentableAmounts() {
        var provider = new Provider(0.1);
        assertTrue(provider.apply(CurrencyAction.Action.SET, "0.3").success());
        assertEquals(0.3, provider.balance, 0.000000001);
        assertFalse(provider.apply(CurrencyAction.Action.ADD, "9007199254740993").success());
        assertEquals(1, provider.transactions);
    }
    private static final class Provider {
        double balance; boolean failure, account = true; int transactions, creations;
        final OfflinePlayer player = (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
            (proxy, method, args) -> { throw new AssertionError("Must operate on offline account without requesting an online player: " + method.getName()); });
        final Economy economy;
        Provider(double balance) {
            this.balance = balance;
            economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (proxy, method, args) -> {
                if (method.getName().equals("fractionalDigits")) return 2;
                assertSame(player, args[0]);
                return switch (method.getName()) {
                    case "hasAccount" -> account;
                    case "createPlayerAccount" -> { creations++; account = !failure; yield account; }
                    case "getBalance" -> this.balance;
                    case "has" -> this.balance >= (double) args[1];
                    case "depositPlayer", "withdrawPlayer" -> {
                        transactions++;
                        if (failure) yield new EconomyResponse(0, this.balance, EconomyResponse.ResponseType.FAILURE, "Provider refused");
                        this.balance += (method.getName().equals("depositPlayer") ? 1 : -1) * (double) args[1];
                        yield new EconomyResponse((double) args[1], this.balance, EconomyResponse.ResponseType.SUCCESS, "");
                    }
                    default -> throw new AssertionError(method.getName());
                };
            });
        }
        CurrencyAction.Result apply(CurrencyAction.Action action, String amount) {
            return VaultCurrency.apply(economy, player, new CurrencyAction.Operation(CurrencyAction.Type.VAULT, action, "default", new BigDecimal(amount), "Alice", ""));
        }
    }
}
