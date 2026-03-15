package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;

/**
 * This class is just a wrapper around CCL ScriptSupplier
 */
public class ScalusScriptSupplier implements scalus.bloxbean.ScriptSupplier {
    private ScriptSupplier cclScriptSupplier;

    public ScalusScriptSupplier(ScriptSupplier scriptSupplier) {
        this.cclScriptSupplier = scriptSupplier;
    }

    @Override
    public PlutusScript getScript(String scriptHash) {
        return cclScriptSupplier.getScript(scriptHash).orElse(null);
    }
}
