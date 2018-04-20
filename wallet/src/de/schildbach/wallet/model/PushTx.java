package de.schildbach.wallet.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Created by rohanagarwal94 on 10/3/18.
 */

public class PushTx {

    @SerializedName("tx")
    @Expose
    private String tx;

    public PushTx(String tx) {
        this.tx = tx;
    }

    public String getTx() {
        return tx;
    }

    public void setTx(String tx) {
        this.tx = tx;
    }
}
