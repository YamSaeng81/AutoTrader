import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local witness only: counters can say drained between dequeue and the worker's active lock. */
public class DrainWitness {
    public static void main(String[] args) throws Exception {
        CountDownLatch allowWorker = new CountDownLatch(1);
        CountDownLatch dequeued = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean(false);
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>() {
            @Override public Runnable take() throws InterruptedException {
                Runnable task = super.take();
                dequeued.countDown();
                allowWorker.await();
                return task;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
                queue);
        try {
            pool.prestartAllCoreThreads();
            pool.execute(() -> { executed.set(true); completed.countDown(); });
            if (!dequeued.await(5, TimeUnit.SECONDS)) throw new AssertionError("task not dequeued");
            boolean drained = pool.getQueue().isEmpty() && pool.getActiveCount() == 0
                    && pool.getTaskCount() == pool.getCompletedTaskCount();
            System.out.println("before_worker: drained=" + drained + " executed=" + executed.get()
                    + " queue=" + pool.getQueue().size() + " active=" + pool.getActiveCount()
                    + " tasks=" + pool.getTaskCount() + " completed=" + pool.getCompletedTaskCount());
            if (!drained || executed.get()) throw new AssertionError("witness failed");
            allowWorker.countDown();
            if (!completed.await(5, TimeUnit.SECONDS)) throw new AssertionError("task did not complete");
            System.out.println("after_worker: executed=" + executed.get());
        } finally { allowWorker.countDown(); pool.shutdownNow(); }
        System.out.println("This proves a wait-predicate gap, not the cause of the original Spring test failure.");
    }
}
