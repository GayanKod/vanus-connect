package com.linkall.source.aws.sns;

import com.linkall.source.aws.utils.AwsHelper;
import com.linkall.source.aws.utils.SNSUtil;
import com.linkall.vance.common.config.ConfigUtil;
import com.linkall.vance.core.Adapter;
import com.linkall.vance.core.Source;
import com.linkall.vance.core.http.HttpClient;

import com.linkall.vance.core.http.HttpResponseInfo;
import io.cloudevents.CloudEvent;
import io.cloudevents.http.vertx.VertxMessageFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

public class SnsSource implements Source {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnsSource.class);
    private static final AtomicInteger eventNum = new AtomicInteger(0);
    private static final Vertx vertx = Vertx.vertx();
    private static Router router;
    private HttpServer httpServer;
    private HttpResponseInfo handlerRI;
    private static final WebClient webClient = WebClient.create(vertx);

    @Override
    public void start(){
        AwsHelper.checkCredentials();
        SnsAdapter adapter = (SnsAdapter) getAdapter();

        String snsTopicArn = ConfigUtil.getString("topic_arn");
        String region = SNSUtil.getRegion(snsTopicArn);
        String host = ConfigUtil.getString("endpoint");
        String protocol = ConfigUtil.getString("protocol");

        SnsClient snsClient = SnsClient.builder().region(Region.of(region)).build();

        String subscribeArn = "";
        try {
            subscribeArn =  SNSUtil.subHTTPS(snsClient, snsTopicArn, host, protocol);
        }catch (SnsException e){
            LOGGER.error(e.awsErrorDetails().errorMessage());
            snsClient.close();
            System.exit(1);
        }

        this.httpServer = vertx.createHttpServer();
        this.router = Router.router(vertx);
        this.handlerRI = new HttpResponseInfo(200, "Receive success, deliver CloudEvents to"
                + ConfigUtil.getVanceSink() + "success", 500, "Receive success, deliver CloudEvents to"
                + ConfigUtil.getVanceSink() + "failure");
        this.router.route("/").handler(request-> {
            String messageType = request.request().getHeader("x-amz-sns-message-type");
            request.request().bodyHandler(body->{
                JsonObject jsonObject = body.toJsonObject();
                String token = jsonObject.getString("Token");
                if(!SNSUtil.verifySignatrue(new ByteArrayInputStream(body.getBytes()), region)){
                    HttpResponseInfo info = this.handlerRI;
                    request.response().setStatusCode(info.getFailureCode());
                    request.response().end(info.getFailureChunk());
                }else{
                    //confirm sub or unSub
                    LOGGER.info("verify signature successful");
                    if(messageType.equals("SubscriptionConfirmation") || messageType.equals("UnsubscribeConfirmation")){
                        try{
                            SNSUtil.confirmSubHTTPS(snsClient, token, snsTopicArn);
                        }catch (SnsException e){
                            LOGGER.error(e.awsErrorDetails().errorMessage());
                            snsClient.close();
                            System.exit(1);
                        }
                    }

                    CloudEvent ce = adapter.adapt(request.request(), body);

                    Future<HttpResponse<Buffer>> responseFuture;
                    String vanceSink = ConfigUtil.getVanceSink();
                    responseFuture = VertxMessageFactory.createWriter(webClient.postAbs(vanceSink))
                            .writeStructured(ce, "application/cloudevents+json");

                    responseFuture.onSuccess(resp->{
                       LOGGER.info("send CloudEvent to " + vanceSink + " success");
                       eventNum.getAndAdd(1);
                       LOGGER.info("send " + eventNum + " CloudEvents in total");
                       HttpResponseInfo info = this.handlerRI;
                       request.response().setStatusCode(info.getSuccessCode());
                       request.response().end(info.getSuccessChunk());
                    });
                    responseFuture.onFailure(resp->{
                        LOGGER.error("send CloudEvent to " + vanceSink + " failure");
                        LOGGER.info("send " + eventNum + " CloudEvents in total");
                    });

                }

            });
        });

        this.httpServer.requestHandler(this.router);

        int port = Integer.parseInt(ConfigUtil.getPort());
        this.httpServer.listen(port, (server) -> {
            if (server.succeeded()) {
                LOGGER.info("HttpServer is listening on port: " + ((HttpServer)server.result()).actualPort());
            } else {
                LOGGER.error(server.cause().getMessage());
            }
        });

        String finalSubscribeArn = subscribeArn;
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            try{
                SNSUtil.unSubHTTPS(snsClient, finalSubscribeArn);
            }catch (SnsException e){
                LOGGER.error(e.awsErrorDetails().errorMessage());
            }
            snsClient.close();

            LOGGER.info("shut down!");
        }));

    }

    @Override
    public Adapter getAdapter() {
        return new SnsAdapter();
    }

}
