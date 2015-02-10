package it.unimi.di.law.warc.filters;

/*		 
 * Copyright (C) 2004-2013 Paolo Boldi, Massimo Santini, and Sebastiano Vigna 
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 3 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

import it.unimi.di.law.bubing.util.BURL;
import it.unimi.dsi.fastutil.ints.AbstractIntComparator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.net.URI;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// RELEASE-STATUS: DIST

/** A filter accepting only URIs whose path does not contain too many duplicate segments.
 * 
 * <p>It is not uncommon to find URIs generated by badly configured 404
 * pages that look like <samp>http://example.com/foo/bar/foo/bar/&hellip;</samp>.
 * This filter will not accept such URIs if some sequence of consecutive segments
 * appears more times than a given threshold.
 * 
 * <p>This implementation uses ideas from &ldquo;Linear&ndash;Time Longest&ndash;Common&ndash;Prefix
 * Computation in Suffix Arrays and Its Applications&rdquo;, by Toru Kasai, Gunho Lee, Hiroki Arimura,
 * Setsuo Arikawa, and Kunsoo Park, in <i>Proc. of the 12th Annual Symposium on 
 * Combinatorial Pattern Matching</i>,
 * volume 2089 of Lecture Notes In Computer Science, pages 181&minus;192, Springer-Verlag, 2001, to simulate
 * a suffix-tree visit on a suffix array, and ideas from
 * &ldquo;Simple and flexible detection of contiguous repeats using a suffix tree&rdquo;, by 
 * Jens Stoye and Dan Gusfield, <i>Theoret. Comput. Sci.</i> 270:843&minus;856, 2002,
 * for the linear-time detection of tandem arrays using suffix trees. 
 * 
 * <p>The resulting code is one order of magnitude faster than regular expressions.
 */

public class DuplicateSegmentsLessThan extends AbstractFilter<URI> {

	private static final boolean DEBUG = false;
	private static final boolean ASSERTS = false;
	/** The extra symbol (usually denoted with $ in the literature) added at the end of
	 * a string to force shorter suffixes to come after in lexicographical ordering. */
	private static final char EXTRA_SYMBOL = 65535;
	/** URIs with a duplicate sequence of consecutive segments longer than this value won't be accepted. */
	private final int threshold;
	
	/** Creates a filter that only accepts URIs whose path does contains less duplicate consecutive segments than
	 *  the given threshold.
	 * 
	 * @param threshold the duplicate-segment threshold (at least 2); if a URI contains less than
	 * this number of duplicate consecutive segments it will be accepted.
	 */
	public DuplicateSegmentsLessThan( final int threshold ) {
		if ( threshold < 2 ) throw new IllegalArgumentException( "This filter requires a threshold larger than one" );
		this.threshold = threshold;
	}

	private void matches( final boolean b, final String s ) {
		final Matcher m0 = Pattern.compile( ".*(/.*)\\1{" + ( threshold - 1 ) + ",}/.*" ).matcher( s );
		final Matcher m1 = Pattern.compile( ".*(/.*)\\1{" + ( threshold - 1 ) + ",}" ).matcher( s );
		assert b != ( m0.matches() || m1.matches() ) : s + " (" + ! b + ( ! b ? "" : ", " + 
				( m0.matches() ? m0.group( 1 ) : m1.group( 1 ) ) ) + ")";
	}
	
	/**
	 * Apply the filter to a given URI
	 * 
	 * @param url the URI to be filtered
	 * @return <code>true</code> if the path contains a number of duplicate segments less than a threshold
	 */
	@Override
	public boolean apply( final URI url ) {
		
		final String s = url.getRawPath();
		final int length = s.length();
		final boolean pathEndsWithSlash = s.charAt( length - 1 ) == '/';
		
		final char[] path = new char[ length + 1 + ( ! pathEndsWithSlash? 1 : 0 ) ];
		path[ path.length - 1 ] = EXTRA_SYMBOL; // Usual suffix-array trick
		if ( !pathEndsWithSlash ) path[ path.length - 2 ] = '/'; // To guarantee that each segment ends with a slash
		s.getChars( 0, length, path, 0 );
		
		// Phase 1: count slashes
		int c = 0;
		for( int i = length; i-- != 0; ) if ( path[ i ] == '/') c++;
		if ( c < threshold ) {
			if ( ASSERTS ) matches( true, s );
			return true; // No way
		}

		// Phase 2: allocate and fill start array
		final int[] start = new int[ c ];
		c = 0;
		for( int i = 0; i < length; i++ ) if ( path[ i ] == '/') start[ c++ ] = i;

		// Phase 3: build suffix array for path components and compute largest number of common path segments
		
		final int[] a = new int[ c ];
		for( int i = c; i-- != 0; ) a[ i ] = i;
		
		IntArrays.quickSort( a, 0, c, new AbstractIntComparator() {
			public int compare( final int x, final int y ) {
				if ( x == y ) return 0;
				int j = start[ x ], k = start[ y ];
				while( path[ ++j ] == path[ ++k ] );
				return path[ j ] - path[ k ];
			}
		});

		// Linear-time LCP computation, from Kasai et. al paper.
		final int[] r= new int[ c ];
		for( int i = c; i-- != 0; ) r[ a[ i ] ] = i;

		final int[] lcp = new int[ c + 1 ]; // Last element account for the $ element
		int h = 0;
		int p = 1;
		boolean maxNonZero = false;

		for( int i = 0; i < c; i++ ) {
			if ( r[ i ] > 0 ) {
				int j = a[ r[ i ] - 1 ];
				final int starti = start[ i ];
				final int startj = start[ j ];
				while( path[ starti + p ] == path[ startj + p ] ) {
					if ( path[ starti + p ] == '/' ) h++;
					p++;
				}
				
				lcp[ r[ i ] ] = h;
				if ( h > 0 ) {
					maxNonZero = true;
					// Discard first common segment
					int k = 1;
					while( path[ starti + k ] != '/' ) k++;
					p -= k;
					h--;
				}
				else p = 1;
			}
		}

		if ( ! maxNonZero ) {
			if ( ASSERTS ) matches( true, s );
			return true; // Not a single common prefix
		}
		
		if ( ASSERTS ) {
			final int[] lcp2 = new int[ c + 1 ];
			for( int i = c; i-- != 1; ) {
				final int starti = start[ a[ i - 1 ] ];
				final int startipp = start[ a[ i ] ];
				
				int k = 1;
				int n = 0;
				while( path[ starti + k ] == path[ startipp + k ] ) {
					if ( path[ starti + k ] == '/' ) n++;
					k++;
				}
				
				lcp2[ i ] = n;
			}
			
			assert Arrays.equals( lcp2, lcp );
		}
		
		if ( DEBUG ) System.err.println( "Path: " + Arrays.toString( path ) );
		if ( DEBUG ) System.err.println( "Start: " + Arrays.toString( start ) );
		if ( DEBUG ) System.err.println( "Suffixes: " + Arrays.toString( a ) );
		if ( DEBUG ) System.err.println( "Common paths: " + Arrays.toString( lcp ) );
		
		// Phase 4: Simulate depth-first visit of the suffix tree

		// Simulated visit of the associated suffix tree, always by Kasai et. al.

		// A stack for left extremes and depth, initialised with -1, -1.
		final int[] ls = new int[ c + 1 ], ds = new int[ c + 1 ];
		/* A support array where, while visiting a node, we will store the length of the
		 * maximal arithmetic progression of ratio d among the leaves of the current
		 * node. */ 
		final int[] prog = new int[ c ];
		ls[ 0 ] = ds[ 0 ] = -1;
		p = 1;
		
		int llca, dlca;
		int l, d;
		
		for( int i = 0; i < c; i++ ) {
			llca = i;
			dlca = lcp[ i + 1 ]; // Note that when i == c - 1 then lcp[ i + 1 ] == 0.

			while( ds[ p - 1 ] > dlca ) {
				// Pop (l,d) off the stack
				l = ls[ --p ];
				d = ds[ p ];

				if ( DEBUG ) System.err.printf( "Got triple <" + l + ", " + i + ", " + d + "\n" );
				if ( DEBUG ) System.err.println( IntArrayList.wrap( a ).subList( l, i + 1  ) );
				// Now we have a visit interval start at L, ending at i of depth H
				if ( i - l + 1 >= threshold ) {
					/* Now we have a list of leaves which share a common prefix of length d.
					 * Stoye and Gusfield note that we can find an arithmetic progression of
					 * ratio d among those leaves (e.g., we can find leaves whose associated positions are 
					 * i, i+d, i+2d, ..., i+(k-1)d ) iff those positions 
					 * are the starting points of a tandem array of length k.
					 *  
					 * To do this in linear time, we exploit the fact (noted by Stoye and Gusfield)
					 * that for l <= j <= i, r[ a[ j ] + t * d ] is the position in the string of 
					 * a[ j ] + t * d, which means that a[ j ] + t * d is in the set of 
					 * leaves under examination (i.e., a[ l..i ]) iff r[ a[ j ] + t * d ]
					 * is between l and i (inclusive). 
					 *  
					 * To avoid testing all elements separately (which would require potentially
					 * (i - l + 1) * k tests) we use prog either to remember the length of the longest
					 * increasing progression found starting with the corresponding element of a,
					 * or to remember that an element need not being examined because it cannot lead
					 * to maximal progressions.
					 * 
					 * Starting from each leaf a[ j ], we try to
					 * extent greedily an arithmetic progression of ratio d, and record its length
					 * in prog[ j ]. When examining the following elements, if following the progression
					 * we hit an element with nonzero prog, we can just sum to the current length
					 * the number found thereis and break the loop, as the maximal arithmetic
					 * progression of ratio d from our current position has been already computed.
					 */
					IntArrays.fill( prog, l, i + 1, 0 );
					for( int j = l; j <= i; j++ ) {
						if ( prog[ j ] != 0 ) continue;
						int t = 1, u = a[ j ], k = u, pos;
						for(;;)  {
							k += d; // The next element of the progression
							if ( k >= c ) break;
							pos = r[ k ]; // Its position (in [l..i])
							if ( pos < l || i < pos ) break;
							else if ( prog[ pos ] != 0 ) {
								if ( ASSERTS ) assert prog[ pos ] > 0 : "l="  + l + " , i=" + i + ", j=" + j + ", t=" + t + ", a=" + Arrays.toString( a ) + ", prog=" + Arrays.toString( prog ); 
								t += prog[ pos ];
								break;
							}
							t++;
						}
						if ( t >= threshold ) {
							if ( ASSERTS ) matches( false, s ); 
							return false;
						}
						prog[ j ] = t;
						// We backtrack, putting -1 in all intermediate entries so we won't examine them further
						while( ( k -= d ) != u ) prog[ r[ k ] ] = -1;
					}
				}
				llca = l;

			}
			
			if ( ds[ p - 1 ] < dlca ) {
				// Push (llca, dlca) on the stack
				ls[ p ] = llca;
				ds[ p++ ] = dlca;
			}
		}

		if ( ASSERTS ) matches( true, s );
		return true;
	}

	/**
	 * Get a new <code>DuplicateSegmentsLessThan</code> that will accept only URIs whose path does not contain too many duplicate segments.
	 * 
	 * @param spec a String integer, that is the threshold used by the filter
	 * @return a new <code>DuplicateSegmentsLessThan</code> that will accept only URIs whose path contain a number of duplicate segments less than <code>spec</code>
	 */
	public static DuplicateSegmentsLessThan valueOf( String spec ) {
		return new DuplicateSegmentsLessThan( Integer.parseInt( spec ) );
	}

	/**
	 * A string representation of the state of this object, that is just the threshold used.
	 * 
	 * @return the threshold used by this
	 */
	public String toString() {
		return toString( Integer.toString( threshold ) );
	}	

	/**
	 * Compare this object with a given generic one
	 * 
	 * @return true if <code>x</code> is an instance of <code>DuplicateSegmentsLessThan</code> and the URIs allowed by x are allowed by this and vice versa
	 */
	public boolean equals( Object x ) {
		return x instanceof DuplicateSegmentsLessThan && ((DuplicateSegmentsLessThan)x).threshold == threshold;
	}
	
	public static void main( String arg[] ) {
		// A simple speed test for this filter.
		
		final int rep = Integer.parseInt( arg[ 0 ] );
		final long times = Long.parseLong( arg[ 1 ] );
		
		Pattern p = Pattern.compile( ".*/(.*/)\\1{" + ( rep - 1 ) + ",}.*" );
		Matcher m;
		//String url = "http://example.com/test/foo/bar/foo/bar/foo/mu/foo/bar/foo/bar/foo/bar/";
		//String url = "http://example.com/test/foo/bar1/foo/bar2/foo/mu/foo/bar3/foo/bar4/foo/bar5/test/";
		String uri = "http://example.com/test/foo/bar1/foo/bar2/foo/mu/foo/bar3/foo/bar4/foo/bar5/test/foo/bar1/foo/bar2/foo/mu/foo/bar3/foo/bar4/foo/bar5/test/";
		DuplicateSegmentsLessThan filter = new DuplicateSegmentsLessThan( rep );
		URI buri = BURL.parse( uri );
		
		long start;
		
		System.err.println( "Regex: " + ( ! p.matcher( uri ).matches() ) );
		System.err.println( "Filter: " + filter.apply( buri ) );
		
		for( int k = 10; k-- != 0; ) {
			
			start = -System.currentTimeMillis();
			
			for( long i = times; i-- != 0; ) {
				m = p.matcher( uri );
				m.matches();
			}
			
			start += System.currentTimeMillis();
			System.err.printf( "Regex: %f Kcalls/s\n",  Double.valueOf( times / (double)start ) );
			
			start = -System.currentTimeMillis();
			
			for( long i = times; i-- != 0; ) filter.apply( buri );
			
			start += System.currentTimeMillis();
			System.err.printf( "Filter: %f Kcalls/s\n",  Double.valueOf( times / (double)start ) );
		}
	}

	@Override
	public Filter<URI> copy() {
		return this;
	}
}
