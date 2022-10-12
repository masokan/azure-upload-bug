package com.mycompany.test;

import org.reactivestreams.Subscription;

import reactor.core.CoreSubscriber;

import reactor.core.publisher.Mono;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MonoSubscriber<T> implements CoreSubscriber<T> {
  private T receivedObject;
  private Throwable throwable;
  private Semaphore completionSemaphore;

  public MonoSubscriber() {
    receivedObject = null;
    throwable = null;
    completionSemaphore = new Semaphore(0);
  }

  public boolean waitForCompletion() throws InterruptedException {
    return completionSemaphore.tryAcquire(1, 5*60, TimeUnit.SECONDS);
  }

  public Throwable getThrowable() {
    return throwable;
  }

  public T getReceivedObject() {
    return receivedObject;
  }

  @Override
  public void onSubscribe(Subscription s) {
    s.request(1L);
  }

  @Override
  public void onNext(T object) {
    receivedObject = object;
  }

  @Override
  public void onError(Throwable t) {
    throwable = t;
    completionSemaphore.release(1);
  }

  @Override
  public void onComplete() {
    completionSemaphore.release(1);
  }
}
