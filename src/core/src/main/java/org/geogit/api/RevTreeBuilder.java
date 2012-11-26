/* Copyright (c) 2011 TOPP - www.openplans.org. All rights reserved.
 * This code is licensed under the LGPL 2.1 license, available at the root
 * application directory.
 */
package org.geogit.api;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.geogit.api.RevTree.NORMALIZED_SIZE_LIMIT;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import org.geogit.api.RevObject.TYPE;
import org.geogit.api.plumbing.HashObject;
import org.geogit.api.plumbing.diff.DepthTreeIterator;
import org.geogit.api.plumbing.diff.DepthTreeIterator.Strategy;
import org.geogit.repository.DepthSearch;
import org.geogit.storage.NodeRefPathStorageOrder;
import org.geogit.storage.ObjectDatabase;
import org.geogit.storage.ObjectReader;
import org.geogit.storage.ObjectSerialisingFactory;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

public class RevTreeBuilder {

    /**
     * How many children refs to hold before forcing normalization of the internal data structures
     * into tree buckets on the database
     * 
     * @todo make this configurable
     */
    public static final int DEFAULT_NORMALIZATION_THRESHOLD = 1000 * 100;

    private final ObjectDatabase db;

    protected final ObjectSerialisingFactory serialFactory;

    private final Set<String> deletes;

    private final Map<String, NodeRef> treeChanges;

    private final Map<String, NodeRef> featureChanges;

    protected final TreeMap<Integer, ObjectId> bucketTreesByBucket;

    private int depth;

    protected NodeRefPathStorageOrder storageOrder = new NodeRefPathStorageOrder();

    /**
     * Empty tree constructor, used to create trees from scratch
     * 
     * @param db
     * @param serialFactory
     */
    public RevTreeBuilder(ObjectDatabase db, final ObjectSerialisingFactory serialFactory) {
        this(db, serialFactory, null);
    }

    /**
     * Copy constructor with tree depth
     */
    public RevTreeBuilder(ObjectDatabase db, final ObjectSerialisingFactory serialFactory,
            @Nullable final RevTree copy) {
        this(db, serialFactory, copy, 0);
    }

    /**
     * Copy constructor
     */
    public RevTreeBuilder(ObjectDatabase db, final ObjectSerialisingFactory serialFactory,
            @Nullable final RevTree copy, int depth) {

        checkNotNull(db);
        checkNotNull(serialFactory);
        this.db = db;
        this.depth = depth;
        this.serialFactory = serialFactory;

        this.deletes = Sets.newHashSet();
        this.treeChanges = Maps.newHashMap();
        this.featureChanges = Maps.newHashMap();
        this.bucketTreesByBucket = Maps.newTreeMap();

        if (copy != null) {
            if (copy.trees().isPresent()) {
                checkArgument(!copy.buckets().isPresent());
                for (NodeRef ref : copy.trees().get()) {
                    putInternal(ref);
                }
            }
            if (copy.features().isPresent()) {
                checkArgument(!copy.buckets().isPresent());
                for (NodeRef ref : copy.features().get()) {
                    putInternal(ref);
                }
            }
            if (copy.buckets().isPresent()) {
                checkArgument(!copy.features().isPresent());
                bucketTreesByBucket.putAll(copy.buckets().get());
            }
        }
    }

    /**
     * @param bucket
     * @param ref
     * @return
     */
    private @Nullable
    NodeRef putInternal(NodeRef ref) {
        switch (ref.getType()) {
        case FEATURE:
            return featureChanges.put(ref.getPath(), ref);
        case TREE:
            return treeChanges.put(ref.getPath(), ref);
        default:
            throw new IllegalArgumentException("Only tree or feature refs can be added to a tree: "
                    + ref + " " + ref.getType());
        }
    }

    private RevTree loadTree(final ObjectId subtreeId) {
        ObjectReader<RevTree> reader = serialFactory.createRevTreeReader();
        RevTree subtree = db.get(subtreeId, reader);
        return subtree;
    }

    private Optional<NodeRef> getInternal(final String key, final boolean deep) {
        NodeRef found = featureChanges.get(key);
        if (found == null) {
            found = treeChanges.get(key);
        }
        if (found != null) {
            return Optional.of(found);
        }

        if (!deep) {
            return Optional.absent();
        }
        if (deletes.contains(key)) {
            return Optional.absent();
        }

        final Integer bucket = computeBucket(key);
        final ObjectId subtreeId = bucketTreesByBucket.get(bucket);
        if (subtreeId == null) {
            return Optional.absent();
        }

        RevTree subtree = loadTree(subtreeId);

        DepthSearch depthSearch = new DepthSearch(db, serialFactory);
        Optional<NodeRef> ref = depthSearch.getDirectChild(subtree, key, depth + 1);
        return ref;
    }

    private long sizeOfTree(ObjectId treeId) {
        if (treeId.isNull()) {
            return 0L;
        }
        RevTree tree = loadTree(treeId);
        return tree.size();
    }

    private int numPendingChanges() {
        int totalChanges = featureChanges.size() + treeChanges.size() + deletes.size();
        return totalChanges;
    }

    /**
     * Splits the cached entries into subtrees and saves them, making sure the tree contains either
     * only entries or subtrees
     */
    private RevTree normalize() {
        RevTree unnamedTree;

        if (bucketTreesByBucket.isEmpty() && numPendingChanges() <= NORMALIZED_SIZE_LIMIT) {
            unnamedTree = normalizeToChildren();
        } else {
            unnamedTree = normalizeToBuckets();
            if (unnamedTree.isEmpty()) {
                return RevTree.EMPTY;
            }
            if (unnamedTree.size() <= NORMALIZED_SIZE_LIMIT) {
                unnamedTree = moveBucketsToChildren(unnamedTree);
            }
        }
        return unnamedTree;
    }

    /**
     * @param unnamedTree
     * @return
     */
    private RevTree moveBucketsToChildren(RevTree unnamedTree) {
        checkState(featureChanges.isEmpty());
        DepthTreeIterator iterator = new DepthTreeIterator(unnamedTree, db, Strategy.CHILDREN);
        this.bucketTreesByBucket.clear();
        while (iterator.hasNext()) {
            put(iterator.next());
        }
        return normalizeToChildren();
    }

    /**
     * 
     */
    private RevTree normalizeToChildren() {
        Preconditions.checkState(this.bucketTreesByBucket.isEmpty());
        // remove deletes
        deletes.clear();

        long size = featureChanges.size();
        if (!treeChanges.isEmpty()) {
            for (NodeRef treeRef : treeChanges.values()) {
                size += sizeOf(treeRef);
            }
        }
        Collection<NodeRef> features = featureChanges.values();
        Collection<NodeRef> trees = treeChanges.values();
        RevTreeImpl unnamedTree = RevTreeImpl.createLeafTree(ObjectId.NULL, size, features, trees);
        return unnamedTree;
    }

    private long sizeOf(NodeRef ref) {
        return ref.getType().equals(TYPE.TREE) ? sizeOfTree(ref.getObjectId()) : 1L;
    }

    /**
     * @return
     * 
     */
    private RevTree normalizeToBuckets() {
        // update all inner trees
        final int childDepth = this.depth + 1;
        long accSize = 0;

        try {
            Multimap<Integer, NodeRef> changesByBucket = ArrayListMultimap.create();
            for (Iterator<NodeRef> it = featureChanges.values().iterator(); it.hasNext();) {
                NodeRef change = it.next();
                it.remove();
                Integer bucket = computeBucket(change.getPath());
                changesByBucket.put(bucket, change);
            }
            Preconditions.checkState(featureChanges.isEmpty());
            for (Iterator<NodeRef> it = treeChanges.values().iterator(); it.hasNext();) {
                NodeRef change = it.next();
                it.remove();
                Integer bucket = computeBucket(change.getPath());
                changesByBucket.put(bucket, change);
            }
            Preconditions.checkState(featureChanges.isEmpty());

            final Set<Integer> buckets = ImmutableSet.copyOf(Sets.union(changesByBucket.keySet(),
                    bucketTreesByBucket.keySet()));

            for (Integer bucket : buckets) {
                final RevTreeBuilder subtreeBuilder;
                {
                    final Collection<NodeRef> bucketEntries = changesByBucket.removeAll(bucket);
                    final ObjectId subtreeId = bucketTreesByBucket.get(bucket);
                    if (subtreeId == null) {
                        subtreeBuilder = new RevTreeBuilder(db, serialFactory, null, childDepth);
                    } else {
                        subtreeBuilder = new RevTreeBuilder(db, serialFactory, loadTree(subtreeId),
                                childDepth);
                    }
                    for (String deleted : deletes) {
                        Integer bucketOfDelete = computeBucket(deleted);
                        if (bucket.equals(bucketOfDelete)) {
                            subtreeBuilder.remove(deleted);
                        }
                    }
                    for (NodeRef ref : bucketEntries) {
                        subtreeBuilder.put(ref);
                    }
                }
                final RevTree subtree = subtreeBuilder.build();
                accSize += subtree.size();
                if (subtree.isEmpty()) {
                    bucketTreesByBucket.remove(bucket);
                } else {
                    final ObjectId newSubtreeId = subtree.getId();
                    db.put(newSubtreeId, serialFactory.createRevTreeWriter(subtree));
                    bucketTreesByBucket.put(bucket, newSubtreeId);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return RevTreeImpl.createNodeTree(ObjectId.NULL, accSize, this.bucketTreesByBucket);
    }

    protected final Integer computeBucket(final String path) {
        return this.storageOrder.bucket(path, this.depth);
    }

    /**
     * Gets an entry by key, this is potentially slow.
     * 
     * @param key
     * @return
     */
    public Optional<NodeRef> get(final String key) {
        return getInternal(key, true);
    }

    /**
     * Adds or replaces an element in the tree with the given key.
     * <p>
     * <!-- Implementation detail: If the number of cached entries (entries held directly by this
     * tree) reaches {@link #DEFAULT_NORMALIZATION_THRESHOLD}, this tree will {@link #normalize()}
     * itself.
     * 
     * -->
     * 
     * @param key non null
     * @param value non null
     */
    public RevTreeBuilder put(final NodeRef ref) {
        Preconditions.checkNotNull(ref, "ref can't be null");

        putInternal(ref);
        if (numPendingChanges() >= DEFAULT_NORMALIZATION_THRESHOLD) {
            // hit the split factor modification tolerance, lets normalize
            normalize();
        }
        return this;
    }

    public RevTreeBuilder remove(final String childPath) {
        Preconditions.checkNotNull(childPath, "key can't be null");
        // uses a NodeRef with ObjectId.NULL id signaling the removal
        // of the entry. normalize() is gonna take care of removing it from the subtree
        // subsequently
        featureChanges.remove(childPath);
        treeChanges.remove(childPath);
        deletes.add(childPath);
        return this;
    }

    /**
     * @return the new tree, not saved to the object database. Any bucket tree thouh is saved when
     *         this method returns.
     */
    public RevTree build() {
        RevTree unnamedTree = normalize();
        checkState(bucketTreesByBucket.isEmpty()
                || (featureChanges.isEmpty() && treeChanges.isEmpty()));

        ObjectId treeId = new HashObject().setObject(unnamedTree).call();
        RevTreeImpl namedTree = RevTreeImpl.create(treeId, unnamedTree.size(), unnamedTree);
        return namedTree;
    }
}