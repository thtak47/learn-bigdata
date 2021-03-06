package com.hqbhoho.bigdata.guava.cache.reference;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * describe:
 * <p>
 * 每发生一次GC，都会将WeakReference引用的对象回收        弱引用
 * <p>
 * result:
 * Ref{index=0} add to list...
 * Ref{index=1} add to list...
 * Ref{index=2} add to list...
 * Ref{index=3} add to list...
 * Ref{index=4} add to list...
 * Ref{index=5} add to list...
 * [GC (Allocation Failure) [PSYoungGen: 8702K->936K(9728K)] 8702K->7088K(31744K), 0.0138746 secs] [Times: user=0.05 sys=0.01, real=0.01 secs]
 * Ref{index=6} add to list...
 * Ref{index=5} invoke finalize method and will be gc at once...
 * Ref{index=4} invoke finalize method and will be gc at once...
 * Ref{index=3} invoke finalize method and will be gc at once...
 * Ref{index=2} invoke finalize method and will be gc at once...
 * Ref{index=1} invoke finalize method and will be gc at once...
 * Ref{index=0} invoke finalize method and will be gc at once...
 * Ref{index=7} add to list...
 * Ref{index=8} add to list...
 * Ref{index=9} add to list...
 *
 * @author hqbhoho
 * @version [v1.0]
 * @date 2019/10/15
 */
public class WeakReferenceExample {
    public static void main(String[] args) {
        ArrayList<WeakReference<Ref>> list = new ArrayList<>();
        AtomicInteger count = new AtomicInteger(0);
        for (; ; ) {
            Ref ref = new Ref(count.getAndIncrement());
            WeakReference<Ref> refWeakReference = new WeakReference<>(ref);
            list.add(refWeakReference);
            System.out.println(refWeakReference.get() + " add to list...");
            try {
                TimeUnit.MILLISECONDS.sleep(1000l);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
