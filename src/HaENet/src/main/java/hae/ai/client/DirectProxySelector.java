package hae.ai.client;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

final class DirectProxySelector extends ProxySelector {
    static final DirectProxySelector INSTANCE = new DirectProxySelector();

    private DirectProxySelector() {
    }

    @Override
    public List<Proxy> select(URI uri) {
        return List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
    }
}
