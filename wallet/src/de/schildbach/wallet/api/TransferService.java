package de.schildbach.wallet.api;

import de.schildbach.wallet.model.PushTx;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface TransferService {

    @POST("txs/push")
    Call<String> pushTx(@Body PushTx tx, @Query("token") String token);
}
