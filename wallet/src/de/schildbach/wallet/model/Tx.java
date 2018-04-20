package de.schildbach.wallet.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by rohanagarwal94 on 17/2/18.
 */
public class Tx {

    @SerializedName("ver")
    @Expose
    private long ver;
    @SerializedName("inputs")
    @Expose
    private List<Input> inputs = null;
    @SerializedName("weight")
    @Expose
    private long weight;
    @SerializedName("block_height")
    @Expose
    private long blockHeight;
    @SerializedName("relayed_by")
    @Expose
    private String relayedBy;
    @SerializedName("out")
    @Expose
    private List<Out> out = null;
    @SerializedName("lock_time")
    @Expose
    private long lockTime;
    @SerializedName("result")
    @Expose
    private long result;
    @SerializedName("size")
    @Expose
    private long size;
    @SerializedName("time")
    @Expose
    private long time;
    @SerializedName("tx_index")
    @Expose
    private long txIndex;
    @SerializedName("vin_sz")
    @Expose
    private long vinSz;
    @SerializedName("hash")
    @Expose
    private String hash;
    @SerializedName("vout_sz")
    @Expose
    private long voutSz;

    public long getVer() {
        return ver;
    }

    public void setVer(long ver) {
        this.ver = ver;
    }

    public List<Input> getInputs() {
        return inputs;
    }

    public void setInputs(List<Input> inputs) {
        this.inputs = inputs;
    }

    public long getWeight() {
        return weight;
    }

    public void setWeight(long weight) {
        this.weight = weight;
    }

    public long getBlockHeight() {
        return blockHeight;
    }

    public void setBlockHeight(long blockHeight) {
        this.blockHeight = blockHeight;
    }

    public String getRelayedBy() {
        return relayedBy;
    }

    public void setRelayedBy(String relayedBy) {
        this.relayedBy = relayedBy;
    }

    public List<Out> getOut() {
        return out;
    }

    public void setOut(List<Out> out) {
        this.out = out;
    }

    public long getLockTime() {
        return lockTime;
    }

    public void setLockTime(long lockTime) {
        this.lockTime = lockTime;
    }

    public long getResult() {
        return result;
    }

    public void setResult(long result) {
        this.result = result;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public long getTxIndex() {
        return txIndex;
    }

    public void setTxIndex(long txIndex) {
        this.txIndex = txIndex;
    }

    public long getVinSz() {
        return vinSz;
    }

    public void setVinSz(long vinSz) {
        this.vinSz = vinSz;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public long getVoutSz() {
        return voutSz;
    }

    public void setVoutSz(long voutSz) {
        this.voutSz = voutSz;
    }
}