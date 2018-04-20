package de.schildbach.wallet.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by rohanagarwal94 on 16/4/17.
 */
public class AddressResponse {

    @SerializedName("hash160")
    @Expose
    private String hash160;
    @SerializedName("address")
    @Expose
    private String address;
    @SerializedName("n_tx")
    @Expose
    private long nTx;
    @SerializedName("total_received")
    @Expose
    private long totalReceived;
    @SerializedName("total_sent")
    @Expose
    private long totalSent;
    @SerializedName("final_balance")
    @Expose
    private long finalBalance;
    @SerializedName("txs")
    @Expose
    private List<Tx> txs = null;

    public String getHash160() {
        return hash160;
    }

    public void setHash160(String hash160) {
        this.hash160 = hash160;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public long getNTx() {
        return nTx;
    }

    public void setNTx(long nTx) {
        this.nTx = nTx;
    }

    public long getTotalReceived() {
        return totalReceived;
    }

    public void setTotalReceived(long totalReceived) {
        this.totalReceived = totalReceived;
    }

    public long getTotalSent() {
        return totalSent;
    }

    public void setTotalSent(long totalSent) {
        this.totalSent = totalSent;
    }

    public long getFinalBalance() {
        return finalBalance;
    }

    public void setFinalBalance(long finalBalance) {
        this.finalBalance = finalBalance;
    }

    public List<Tx> getTxs() {
        return txs;
    }

    public void setTxs(List<Tx> txs) {
        this.txs = txs;
    }
}
