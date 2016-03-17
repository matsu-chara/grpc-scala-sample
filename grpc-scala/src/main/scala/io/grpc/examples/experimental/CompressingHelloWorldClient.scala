/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.grpc.examples.experimental

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.logging.Logger

import com.google.common.util.concurrent.Uninterruptibles
import io.grpc.ClientCall.Listener
import io.grpc._
import io.grpc.examples.helloworld.helloworld.{GreeterGrpc, HelloReply, HelloRequest}
import io.grpc.internal.GrpcUtil

/**
 * A simple client that requests a greeting from the
 * [[io.grpc.examples.helloworld.HelloWorldServer]].
 *
 * <p>This class should act a a drop in replacement for
 * [[io.grpc.examples.helloworld.HelloWorldClient]].
 */
object CompressingHelloWorldClient {
  private val logger: Logger = Logger.getLogger(classOf[CompressingHelloWorldClient].getName)

  /**
   * Greet server. If provided, the first element of {{{args}}} is the name to use in the
   * greeting.
   */
  def main(args: Array[String]) {
    val client = new CompressingHelloWorldClient("localhost", 50051)
    try {
      val user = args.headOption.getOrElse("world")
      client.greet(user)
    } finally {
      client.shutdown()
    }
  }
}

class CompressingHelloWorldClient(host: String, port: Int) {
  private final val channel: ManagedChannel =
    ManagedChannelBuilder.forAddress(host, port).usePlaintext(true).build

  def shutdown() = {
    channel.shutdown.awaitTermination(5, TimeUnit.SECONDS)
  }

  /** Say hello to server. */
  def greet(name: String) {
    val call = channel.newCall(GreeterGrpc.METHOD_SAY_HELLO, CallOptions.DEFAULT)
    val latch = new CountDownLatch(1)

    call.start(new Listener[HelloReply] {
      override def onHeaders(headers: Metadata) {
        super.onHeaders(headers)
        val encoding = headers.get(GrpcUtil.MESSAGE_ENCODING_KEY)
        if (encoding == null) {
          throw new RuntimeException("No compression selected!")
        }
      }

      override def onMessage(message: HelloReply): Unit = {
        super.onMessage(message)
        CompressingHelloWorldClient.logger.info("Greeting: " + message.message)
        latch.countDown()
      }

      override def onClose(status: Status, trailers: Metadata): Unit = {
        latch.countDown()
        if (!status.isOk) {
          throw status.asRuntimeException()
        }
      }
    }, new Metadata())
    call.setMessageCompression(true)
    call.sendMessage(HelloRequest(name))
    call.request(1)
    call.halfClose()

    Uninterruptibles.awaitUninterruptibly(latch, 100, TimeUnit.SECONDS)
  }
}

