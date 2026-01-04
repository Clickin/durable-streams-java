package io.github.clickin.client;

import io.github.clickin.core.Offset;
import io.github.clickin.core.StreamEvent;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Internal implementation of the Catch-Up loop.
 *
 * <p>This class is not intended to be used directly by clients.
 */
public final class CatchUpLoop {

    private final DurableStreamsClient client;
    private final ReadRequest request;

    public CatchUpLoop(DurableStreamsClient client, ReadRequest request) {
        this.client = client;
        this.request = request;
    }

    public Flow.Publisher<StreamEvent> publisher() {
        SubmissionPublisher<StreamEvent> pub = new SubmissionPublisher<>();
        Thread t = new Thread(() -> run(pub), "durable-streams-catchup");
        t.setDaemon(true);
        t.start();
        return pub;
    }

    private void run(SubmissionPublisher<StreamEvent> pub) {
        Offset cur = request.offset() == null ? Offset.beginning() : request.offset();
        String etag = request.ifNoneMatch();

        try {
            while (!pub.isClosed()) {
                ReadResult result = client.readCatchUp(new ReadRequest(request.streamUrl(), cur, etag));
                if (result.status() == 304) {
                    if (result.upToDate()) {
                        if (result.nextOffset() != null) cur = result.nextOffset();
                        pub.submit(new StreamEvent.UpToDate(cur));
                        break;
                    }
                    continue;
                }

                if (result.status() != 200) {
                    pub.closeExceptionally(new IllegalStateException("catch-up status=" + result.status()));
                    return;
                }

                byte[] body = result.body();
                if (body != null && body.length > 0) {
                    pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(body), result.contentType()));
                }

                if (result.nextOffset() != null) cur = result.nextOffset();
                etag = result.etag();

                if (result.upToDate()) {
                    pub.submit(new StreamEvent.UpToDate(cur));
                    break;
                }
            }
            pub.close();
        } catch (Exception e) {
            pub.closeExceptionally(e);
        }
    }
}