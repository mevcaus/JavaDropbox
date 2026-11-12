package com.javadropbox.javadropbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passes connections on to a service, on a port of its own, and can be stopped and started again on
 * the same port: to the app, the service goes away, with every connection it had, and comes back.
 */
public final class TcpProxy implements AutoCloseable {

  private final String host;
  private final int targetPort;
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private volatile ServerSocket server;
  // Whether connections are taken and then left unanswered.
  private volatile boolean hanging;
  private int port;

  /** A proxy to the service at {@code url}'s host and port, started. */
  public TcpProxy(String url) {
    URI uri = URI.create(url);
    this.host = uri.getHost();
    this.targetPort = uri.getPort();
    start();
  }

  /** The proxy's URL, for the service's scheme. */
  public String url() {
    return "http://127.0.0.1:" + port;
  }

  /** Takes connections, and passes them on, again, on the port it had. */
  public synchronized void start() {
    hanging = false;
    if (server != null && !server.isClosed()) {
      return;
    }
    try {
      ServerSocket listening = new ServerSocket();
      listening.setReuseAddress(true);
      listening.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
      port = listening.getLocalPort();
      server = listening;
      Thread.ofVirtual().start(() -> accept(listening));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Takes connections but never answers, like a service that hangs, and drops every one it has. */
  public synchronized void hang() {
    start();
    hanging = true;
    sockets.forEach(TcpProxy::close);
    sockets.clear();
  }

  /** Refuses connections, and drops every one it has. */
  public synchronized void stop() {
    close(server);
    sockets.forEach(TcpProxy::close);
    sockets.clear();
  }

  @Override
  public void close() {
    stop();
  }

  private void accept(ServerSocket listening) {
    while (!listening.isClosed()) {
      try {
        Socket client = listening.accept();
        sockets.add(client);
        if (hanging) {
          continue;
        }
        Socket upstream = new Socket(host, targetPort);
        sockets.add(upstream);
        Thread.ofVirtual().start(() -> pump(client, upstream));
        Thread.ofVirtual().start(() -> pump(upstream, client));
      } catch (IOException e) {
        // Stopped.
      }
    }
  }

  private static void pump(Socket from, Socket to) {
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      in.transferTo(out);
    } catch (IOException e) {
      // One side went away.
    } finally {
      close(from);
      close(to);
    }
  }

  private static void close(AutoCloseable closeable) {
    try {
      if (closeable != null) {
        closeable.close();
      }
    } catch (Exception e) {
      // Already gone.
    }
  }
}
