package inceris.rcc.paper;

import inceris.rcc.common.CurrencyAction;
import net.milkbowl.vault.economy.*;
import org.bukkit.*;

/** Kept separate so servers without Vault can still load RCC. */
final class VaultCurrency {
    private VaultCurrency() {}
    static CurrencyAction.Result apply(OfflinePlayer player, CurrencyAction.Operation operation) {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) return CurrencyAction.Result.failure("No Vault economy provider is registered");
        return apply(registration.getProvider(), player, operation);
    }
    static CurrencyAction.Result apply(Economy economy, OfflinePlayer player, CurrencyAction.Operation operation) {
        if (!operation.currency().isEmpty() && !operation.currency().equalsIgnoreCase("default")) return CurrencyAction.Result.failure("Vault only supports currency: default");
        double amount = operation.amount().doubleValue();
        if (java.math.BigDecimal.valueOf(amount).compareTo(operation.amount()) != 0) return CurrencyAction.Result.failure("Amount exceeds Vault's numeric precision");
        int digits = economy.fractionalDigits();
        if (digits >= 0 && operation.amount().stripTrailingZeros().scale() > digits) return CurrencyAction.Result.failure("Amount exceeds the Vault provider's decimal precision");
        if (!economy.hasAccount(player) && !economy.createPlayerAccount(player)) return CurrencyAction.Result.failure("Could not create offline Vault account");
        CurrencyAction.Action action = operation.action();
        if (action == CurrencyAction.Action.SET) {
            double balance = economy.getBalance(player);
            if (!Double.isFinite(balance)) return CurrencyAction.Result.failure("Invalid Vault balance");
            var difference = operation.amount().subtract(java.math.BigDecimal.valueOf(balance));
            if (difference.signum() == 0) return CurrencyAction.Result.ok();
            action = difference.signum() > 0 ? CurrencyAction.Action.ADD : CurrencyAction.Action.REMOVE;
            amount = difference.abs().doubleValue();
            if (java.math.BigDecimal.valueOf(amount).compareTo(difference.abs()) != 0) return CurrencyAction.Result.failure("Balance adjustment exceeds Vault's numeric precision");
        }
        if (action == CurrencyAction.Action.REMOVE && !economy.has(player, amount)) return CurrencyAction.Result.failure("Insufficient Vault balance");
        EconomyResponse response = action == CurrencyAction.Action.ADD ? economy.depositPlayer(player, amount) : economy.withdrawPlayer(player, amount);
        return response.transactionSuccess() ? CurrencyAction.Result.ok() : CurrencyAction.Result.failure("Vault: " + response.errorMessage);
    }
}
