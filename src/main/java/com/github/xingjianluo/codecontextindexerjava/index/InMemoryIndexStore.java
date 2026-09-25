package com.github.xingjianluo.codecontextindexerjava.index;

import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存索引存储：以原子引用持有当前不可变快照。
 *
 * <ul>
 *   <li>读取（{@link #current()}）永远得到某个<b>已完整构建</b>的快照，
 *       刷新以整体替换方式发布，读者不可能观察到扫描中途的半成品；</li>
 *   <li>发布是引用原子操作，配合不可变 record，无需额外加锁即可安全并发读；</li>
 *   <li>世代（generation）由服务层在单飞锁内递增，存储本身不做计数，杜绝计数漂移。</li>
 * </ul>
 */
@Component
public class InMemoryIndexStore {

    private final AtomicReference<IndexSnapshot> current = new AtomicReference<>();
    private volatile Path boundRoot;

    public InMemoryIndexStore() {
    }

    /**
     * 原子发布一个新快照，并绑定其根目录。
     *
     * @return 已发布的快照（同一个引用，便于调用方继续使用）
     */
    public IndexSnapshot publish(IndexSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        this.boundRoot = snapshot.root();
        this.current.set(snapshot);
        return snapshot;
    }

    /**
     * @return 当前已发布快照；从未发布过时返回绑定根目录的空快照（generation=0）
     */
    public IndexSnapshot current() {
        IndexSnapshot snapshot = current.get();
        if (snapshot != null) {
            return snapshot;
        }
        return IndexSnapshot.empty(boundRoot);
    }

    /**
     * @return 当前绑定的根目录；可能为 null（尚未刷新过且未配置 cci.root）
     */
    public Path boundRoot() {
        Path root = boundRoot;
        IndexSnapshot snapshot = current.get();
        return root != null ? root : (snapshot == null ? null : snapshot.root());
    }
}
