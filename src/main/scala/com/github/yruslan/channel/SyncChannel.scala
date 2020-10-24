/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Ruslan Yushchenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * For more information, please refer to <http://opensource.org/licenses/MIT>
 */

package com.github.yruslan.channel

import java.time.Instant
import java.util.concurrent.{Semaphore, TimeUnit}

import scala.concurrent.duration.Duration

class SyncChannel[T] extends Channel[T] {
  protected var syncValue: Option[T] = None

  override def close(): Unit = {
    lock.lock()
    try {
      if (!closed) {
        closed = true
        readWaiters.foreach(w => w.release())
        writeWaiters.foreach(w => w.release())
        crd.signalAll()
        cwr.signalAll()

        writers += 1
        while (syncValue.nonEmpty) {
          cwr.await()
        }
        writers -= 1
      }
    } finally {
      lock.unlock()
    }
  }

  override def send(value: T): Unit = {
    lock.lock()
    try {
      if (closed) {
        throw new IllegalStateException(s"Attempt to send to a closed channel.")
      }

      writers += 1
      while (syncValue.nonEmpty && !closed) {
        cwr.await()
      }
      if (!closed) {
        syncValue = Option(value)
        notifyReaders()

        while (syncValue.nonEmpty && !closed) {
          cwr.await()
        }
        notifyWriters()
      }
      writers -= 1
    } finally {
      lock.unlock()
    }
  }

  override def trySend(value: T): Boolean = {
    lock.lock()
    try {
      if (closed) {
        false
      } else {
        if (!hasCapacity) {
          false
        } else {
          syncValue = Option(value)
          notifyReaders()
          true
        }
      }
    } finally {
      lock.unlock()
    }
  }

  override def trySend(value: T, timeout: Duration): Boolean = {
    if (timeout == Duration.Zero) {
      return trySend(value)
    }

    val infinite = !timeout.isFinite
    val timeoutMilli = if (infinite) 0L else timeout.toMillis

    val start = Instant.now.toEpochMilli

    def elapsedTime(): Long = {
      Instant.now.toEpochMilli - start
    }

    def isTimeoutExpired: Boolean = {
      if (infinite) {
        false
      } else {
        elapsedTime >= timeoutMilli
      }
    }

    def timeLeft(): Long = {
      val timeLeft = timeoutMilli - elapsedTime()
      if (timeLeft < 0L) 0L else timeLeft
    }

    lock.lock()
    try {
      writers += 1
      while (!hasCapacity && !isTimeoutExpired) {
        if (infinite) {
          cwr.await()
        } else {
          cwr.await(timeLeft(), TimeUnit.MILLISECONDS)
        }
      }
      val isSucceeded = syncValue match {
        case Some(_) =>
          false
        case None if closed =>
          false
        case None if !hasCapacity =>
          false
        case None =>
          syncValue = Option(value)
          notifyReaders()
          true
      }
      writers -= 1
      isSucceeded
    } finally {
      lock.unlock()
    }
  }

  override def recv(): T = {
    lock.lock()
    try {
      readers += 1
      if (!closed && syncValue.isEmpty) {
        notifyWriters()
      }
      while (!closed && syncValue.isEmpty) {
        crd.await()
      }

      if (closed && syncValue.isEmpty) {
        throw new IllegalStateException(s"Attempt to receive from a closed channel.")
      }

      val v: T = syncValue.get
      syncValue = None
      readers -= 1
      notifyWriters()
      v
    } finally {
      lock.unlock()
    }
  }

  override def tryRecv(): Option[T] = {
    lock.lock()
    try {
      if (closed && syncValue.isEmpty) {
        None
      } else {
        if (syncValue.isEmpty) {
          None
        } else {
          val v = syncValue
          syncValue = None
          notifyWriters()
          v
        }
      }
    } finally {
      lock.unlock()
    }
  }

  override def tryRecv(timeout: Duration): Option[T] = {
    if (timeout == Duration.Zero) {
      return tryRecv()
    }

    val infinite = !timeout.isFinite
    val timeoutMilli = if (infinite) 0L else timeout.toMillis

    val start = Instant.now.toEpochMilli

    def elapsedTime(): Long = {
      Instant.now.toEpochMilli - start
    }

    def isTimeoutExpired: Boolean = {
      if (infinite) {
        false
      } else {
        elapsedTime >= timeoutMilli
      }
    }

    def timeLeft(): Long = {
      val timeLeft = timeoutMilli - elapsedTime()
      if (timeLeft < 0L) 0L else timeLeft
    }

    lock.lock()
    try {
      readers += 1
      while (!closed && syncValue.isEmpty && !isTimeoutExpired) {
        if (infinite) {
          crd.await()
        } else {
          crd.await(timeLeft(), TimeUnit.MILLISECONDS)
        }
      }
      readers -= 1

      fetchValueOpt()
    } finally {
      lock.unlock()
    }
  }

  override def isClosed: Boolean = {
    if (syncValue.nonEmpty) {
      false
    } else {
      closed
    }
  }

  override protected def hasCapacity: Boolean = {
    syncValue.isEmpty && (readers > 0 || readWaiters.nonEmpty)
  }

  override protected def hasMessages: Boolean = {
    syncValue.isDefined
  }

  protected def fetchValueOpt(): Option[T] = {
    if (syncValue.nonEmpty) {
      notifyWriters()
    }
    val v = syncValue
    syncValue = None
    v
  }

}
