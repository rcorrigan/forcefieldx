//******************************************************************************
//
// File:    DynamicLongSchedule.java
// Package: edu.rit.pj
// Unit:    Class edu.rit.pj.DynamicLongSchedule
//
// This Java source file is copyright (C) 2009 by Alan Kaminsky. All rights
// reserved. For further information, contact the author, Alan Kaminsky, at
// ark@cs.rit.edu.
//
// This Java source file is part of the Parallel Java Library ("PJ"). PJ is free
// software; you can redistribute it and/or modify it under the terms of the GNU
// General Public License as published by the Free Software Foundation; either
// version 3 of the License, or (at your option) any later version.
//
// PJ is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the GNU
// General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a module
// which is not derived from or based on this library. If you modify this library,
// you may extend this exception to your version of the library, but you are not
// obligated to do so. If you do not wish to do so, delete this exception
// statement from your version.
//
// A copy of the GNU General Public License is provided in the file gpl.txt. You
// may also obtain a copy of the GNU General Public License on the World Wide
// Web at http://www.gnu.org/licenses/gpl.html.
//
//******************************************************************************
package edu.rit.pj;

import java.util.concurrent.atomic.AtomicLong;

import edu.rit.util.LongRange;

/**
 * Class DynamicLongSchedule provides a dynamic schedule object. The loop index
 * is type <code>long</code>. The loop iterations are apportioned into chunks of a
 * given size (a given number of iterations per chunk). Each parallel team
 * thread repeatedly performs the next available chunk of iterations until there
 * are no more chunks. The final chunk may be smaller than the given chunk size.
 *
 * @author Alan Kaminsky
 * @version 18-Nov-2009
 */
class DynamicLongSchedule
        extends LongSchedule {

// Hidden data members.
    // Loop iteration range.
    private LongRange myLoopRange;

    // Number of iterations already handed out.
    private AtomicLong N1 = new AtomicLong();

    // Chunk size.
    private long N2;

// Exported constructors.
    /**
     * Construct a new dynamic schedule object with a chunk size of 1.
     */
    public DynamicLongSchedule() {
        this(1);
    }

    /**
     * Construct a new dynamic schedule object with the given chunk size.
     *
     * @param theChunkSize Chunk size.
     * @exception IllegalArgumentException (unchecked exception) Thrown if
     * <code>theChunkSize</code> is less than 1.
     */
    public DynamicLongSchedule(long theChunkSize) {
        super();
        if (theChunkSize < 1) {
            throw new IllegalArgumentException("DynamicLongSchedule(): Chunk size = " + theChunkSize
                    + " illegal");
        }
        N2 = theChunkSize;
    }

    /**
     * Construct a new dynamic schedule object. This constructor is for use by
     * the <code>LongSchedule.parse()</code> method. <code>args</code> must be an array
     * of one string, namely the chunk size, an integer &gt;= 1.
     *
     * @param args Array of argument strings.
     * @exception IllegalArgumentException (unchecked exception) Thrown if
     * <code>args</code> is not an array of one string. Thrown if the chunk size is
     * less than 1.
     */
    public DynamicLongSchedule(String[] args) {
        this(getChunkSize(args));
    }

    private static long getChunkSize(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("DynamicLongSchedule(): Usage: -Dpj.schedule=dynamic or -Dpj.schedule=\"dynamic(<n>)\"");
        }
        long theChunkSize;
        try {
            theChunkSize = Long.parseLong(args[0]);
        } catch (NumberFormatException exc) {
            throw new IllegalArgumentException("DynamicLongSchedule(): Chunk size = " + args[0]
                    + " illegal");
        }
        return theChunkSize;
    }

    /**
     * Determine if this schedule is a fixed schedule. For a parallel team with
     * <I>K</I> threads, a fixed schedule partitions the loop index range into
     * exactly <I>K</I> chunks, one chunk for each thread, each chunk with
     * predetermined upper and lower bounds.
     *
     * @return True if this is a fixed schedule, false otherwise.
     */
    public boolean isFixedSchedule() {
        return false;
    }

// Hidden operations.
    /**
     * {@inheritDoc}
     *
     * Start generating chunks of iterations for a parallel for loop using this
     * schedule.
     * <P>
     * The <code>start()</code> method is only called by a single thread in the
     * Parallel Java middleware.
     */
    public void start(int K,
            LongRange theLoopRange) {
        myLoopRange = theLoopRange;
        N1.set(0);
    }

    /**
     * {@inheritDoc}
     *
     * Obtain the next chunk of iterations for the given thread index. If there
     * are more iterations, a range object is returned whose lower bound, upper
     * bound, and stride specify the chunk of iterations to perform. The
     * returned range object's stride is the same as that given to the
     * <code>start()</code> method. The returned range object's lower bound and
     * upper bound are contained within the range given to the <code>start()</code>
     * method. If there are no more iterations, null is returned.
     * <P>
     * The <code>next()</code> method is called by multiple parallel team threads in
     * the Parallel Java middleware. The <code>next()</code> method must be multiple
     * thread safe.
     */
    public LongRange next(int theThreadIndex) {
        for (;;) {
            long oldN1 = N1.get();
            LongRange result = myLoopRange.chunk(oldN1, N2);
            long N = result.length();
            if (N == 0) {
                return null;
            }
            long newN1 = oldN1 + N;
            if (N1.compareAndSet(oldN1, newN1)) {
                return result;
            }
        }
    }

}
