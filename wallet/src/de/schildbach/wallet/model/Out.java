package de.schildbach.wallet.model;

/**
 * Created by rohanagarwal94 on 17/2/18.
 */
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Out {

    @SerializedName("spent")
    @Expose
    private Boolean spent;
    @SerializedName("tx_index")
    @Expose
    private long txIndex;
    @SerializedName("type")
    @Expose
    private long type;
    @SerializedName("addr")
    @Expose
    private String addr;
    @SerializedName("value")
    @Expose
    private long value;
    @SerializedName("n")
    @Expose
    private long n;
    @SerializedName("script")
    @Expose
    private String script;

    public Boolean getSpent() {
        return spent;
    }

    public void setSpent(Boolean spent) {
        this.spent = spent;
    }

    public long getTxIndex() {
        return txIndex;
    }

    public void setTxIndex(long txIndex) {
        this.txIndex = txIndex;
    }

    public long getType() {
        return type;
    }

    public void setType(long type) {
        this.type = type;
    }

    public String getAddr() {
        return addr;
    }

    public void setAddr(String addr) {
        this.addr = addr;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }

    public long getN() {
        return n;
    }

    public void setN(long n) {
        this.n = n;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }
}