
package io.vertx.demo.react;

import static rx.Observable.from;
import static rx.Observable.zip;
import io.vertx.rxcore.java.RxVertx;
import io.vertx.rxcore.java.http.RxHttpServer;
import io.vertx.rxcore.java.http.RxServerWebSocket;

import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import rx.Observable;
import rx.Subscription;

public class HttpServer
{
    private static final JsonObject METERS_BUS_REQUEST = new JsonObject().putString("action", "meters");
    private static final JsonObject HISTOGRAMS_BUS_REQUEST = new JsonObject().putString("action",
        "histograms");

    public HttpServer(final JsonObject conf, final RxVertx rx)
    {
        final RouteMatcher routeMatcher = newRouteMatcher(conf, rx);

        final RxHttpServer httpServer = rx.createHttpServer();
        httpServer.http().subscribe(req -> routeMatcher.handle(req));

        httpServer.websocket().subscribe(ws -> handleWebSocket(ws, conf, rx));

        final int httpPort = conf.getObject("http").getInteger("port");
        final String httpHost = conf.getObject("http").getString("host");
        httpServer.coreHttpServer().listen(httpPort, httpHost);
    }

    private RouteMatcher newRouteMatcher(final JsonObject conf, final RxVertx rx)
    {
        final String metricsAddress = conf.getObject("metrics").getString("address");

        final RouteMatcher routeMatcher = new RouteMatcher();

        routeMatcher.get(
            "/api/metrics/sources",
            req -> {
                final Observable<JsonObject> meters = observeMetricsSource(metricsAddress,
                    METERS_BUS_REQUEST, "meters", rx);

                final Observable<JsonObject> histograms = observeMetricsSource(metricsAddress,
                    HISTOGRAMS_BUS_REQUEST, "histograms", rx);

                subscribeAndRespondJson(zip(meters, histograms, (jo1, jo2) -> jo1.mergeIn(jo2)), req);
            });

        routeMatcher.get(
            "/api/metrics/:type/:name",
            req -> {
                final String type = req.params().get("type");
                final String name = req.params().get("name");

                subscribeAndRespondJson(
                    rx.eventBus()
                        .<JsonObject, JsonObject> send(metricsAddress,
                            new JsonObject().putString("action", type))
                        .map(msg -> msg.body().getObject(name)), req);
            });

        routeMatcher.getWithRegEx(".*", req -> {
            if (req.path().equals("/"))
            {
                req.response().sendFile("web/index.html");
            }
            else if (!req.path().contains(".."))
            {
                req.response().sendFile("web/" + req.path());
            }
            else
            {
                req.response().setStatusCode(404).end("Not found");
            }
        });

        return routeMatcher;
    }

    private void handleWebSocket(final RxServerWebSocket ws, final JsonObject config, final RxVertx rx)
    {
        final String metricsPath = ws.path().substring("/streams".length()).replace('/', '.');

        final String broadcastBaseAddress = config.getObject("metrics").getString("broadcast.base.address");

        final Subscription subscription = rx.eventBus()
            .<JsonObject> registerHandler(broadcastBaseAddress + metricsPath)
            .subscribe(m -> ws.writeTextFrame(m.body().toString()));

        ws.closeHandler($ -> subscription.unsubscribe());
    }

    private static Observable<JsonObject> observeMetricsSource(final String metricsAddress,
                                                               final JsonObject busRequest,
                                                               final String sourceFieldName,
                                                               final RxVertx rx)
    {
        return rx.eventBus()
            .<JsonObject, JsonObject> send(metricsAddress, busRequest)
            .map(msg -> msg.body())
            .flatMap(jo -> from(jo.getFieldNames()))
            .filter(fn -> !fn.equals("status"))
            .reduce(new JsonArray(), (ja, fn) -> ja.add(fn))
            .map(ja -> new JsonObject().putArray(sourceFieldName, ja));
    }

    private static void subscribeAndRespondJson(final Observable<JsonObject> o, final HttpServerRequest req)
    {
        o.subscribe(
            sources -> req.response().putHeader("Content-Type", "application/json").end(sources.toString()),
            error -> req.response().setStatusCode(500).end(error.getMessage()));
    }
}
