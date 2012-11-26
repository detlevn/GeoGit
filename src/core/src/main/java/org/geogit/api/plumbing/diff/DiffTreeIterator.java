/* Copyright (c) 2011 TOPP - www.openplans.org. All rights reserved.
 * This code is licensed under the LGPL 2.1 license, available at the root
 * application directory.
 */

package org.geogit.api.plumbing.diff;

import java.util.Iterator;

import org.geogit.api.NodeRef;
import org.geogit.api.RevTree;
import org.geogit.repository.DepthSearch;
import org.geogit.storage.ObjectDatabase;
import org.geogit.storage.ObjectSerialisingFactory;

import com.google.common.base.Optional;
import com.google.common.collect.AbstractIterator;

/**
 * 
 * @see DepthSearch
 */
public class DiffTreeIterator extends AbstractIterator<DiffEntry> {

    private ObjectDatabase lookupDb;

    private RevTree rootTree;

    private Iterator<NodeRef> changes;

    private ObjectSerialisingFactory serialFactory;

    public DiffTreeIterator(ObjectDatabase lookupDb, RevTree rootTree, Iterator<NodeRef> changes,
            ObjectSerialisingFactory serialFactory) {
        this.lookupDb = lookupDb;
        this.rootTree = rootTree;
        this.changes = changes;
        this.serialFactory = serialFactory;
    }

    @Override
    protected DiffEntry computeNext() {
        if (!changes.hasNext()) {
            return super.endOfData();
        }
        NodeRef change = changes.next();
        final DepthSearch search = new DepthSearch(lookupDb, serialFactory);
        final Optional<NodeRef> original = search.find(rootTree, change.getPath());
        final DiffEntry diff;
        if (original.isPresent()) {
            if (change.getObjectId().isNull()) {
                diff = new DiffEntry(original.get(), null);// DELETE
            } else {
                diff = new DiffEntry(original.get(), change);// MODIFY
            }
        } else {
            diff = new DiffEntry(null, change);// ADD
        }
        return diff;
    }

}