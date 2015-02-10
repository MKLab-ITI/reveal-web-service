package it.unimi.di.law.warc.records;

/*		 
 * Copyright (C) 2013 Paolo Boldi, Massimo Santini, and Sebastiano Vigna 
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

// RELEASE-STATUS: DIST

import it.unimi.di.law.bubing.util.Util;
import it.unimi.di.law.warc.io.WarcFormatException;
import it.unimi.di.law.warc.util.BoundSessionInputBuffer;
import it.unimi.di.law.warc.util.ByteArraySessionOutputBuffer;
import it.unimi.dsi.util.XorShift1024StarRandomGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.ProtocolVersion;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.http.message.BasicLineFormatter;
import org.apache.http.message.HeaderGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

/** An abstract implementation of a basic {@link WarcRecord}. */
public abstract class AbstractWarcRecord extends AbstractHttpMessage implements WarcRecord {

	private static final Logger LOGGER = LoggerFactory.getLogger( AbstractWarcRecord.class );
	private static final XorShift1024StarRandomGenerator RNG = new XorShift1024StarRandomGenerator();
	private static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone( "UTC" );
	
	protected final HeaderGroup warcHeaders;

	/** BUilds a record, optionally given the warcHeaders.
	 * 
	 * @param warcHeaders the WARC headers, may be {@code null}.
	 * @see AbstractWarcRecord#AbstractWarcRecord(URI,HeaderGroup)
	 */
	public AbstractWarcRecord( final HeaderGroup warcHeaders ) {
		this( null, warcHeaders );
	}
	
	/** BUilds a record, optionally given the target URI and the warcHeaders.
	 * 
	 * If the headers are {@code null} or the {@link WarcHeader.Name#WARC_RECORD_ID} header is absent, it will be generated at random, 
	 * similarly if the headers are {@code null} or the {@link WarcHeader.Name#WARC_DATE} header absent, it will be set to the  current time.
	 * If the target URI is not {@code null} and the {@link WarcHeader.Name#WARC_TARGET_URI} header is not set, it will be set to the given vaule.
	 * 
	 * @param targetURI the target URI, may be {@code null}.
	 * @param warcHeaders the WARC headers, may be {@code null}.
	 */
	public AbstractWarcRecord( final URI targetURI, final HeaderGroup warcHeaders ) {
		this.warcHeaders = warcHeaders == null ? new HeaderGroup() : warcHeaders;
		final UUID id;
		synchronized ( RNG ) {
			id = new UUID( RNG.nextLong(), RNG.nextLong() );
		}
		WarcHeader.addIfNotPresent( this.warcHeaders, WarcHeader.Name.WARC_RECORD_ID, WarcHeader.formatId( id ) );
		WarcHeader.addIfNotPresent( this.warcHeaders, WarcHeader.Name.WARC_DATE, WarcHeader.formatDate( Calendar.getInstance( UTC_TIMEZONE ) ) );
		if ( targetURI != null ) WarcHeader.addIfNotPresent( this.warcHeaders, WarcHeader.Name.WARC_TARGET_URI, targetURI.toString() ); // TODO: check with Seba that toString makes sense
	}

	@Override
	public ProtocolVersion getProtocolVersion() {
		return PROTOCOL_VERSION;
	}

	@Override
	public HeaderGroup getWarcHeaders() {
		return this.warcHeaders;
	}

	@Override
	public Header getWarcHeader( final WarcHeader.Name header ) {
		return WarcHeader.getFirstHeader( this.warcHeaders, header );
	}
	
	@Override
	public UUID getWarcRecordId() {
		final Header header = WarcHeader.getFirstHeader( this.warcHeaders, WarcHeader.Name.WARC_RECORD_ID );
		if ( header == null ) throw new IllegalStateException( WarcHeader.Name.WARC_RECORD_ID + " mandatory header not present" );
		UUID uuid;
		try {
			uuid = WarcHeader.parseId( header.getValue() );
		} catch ( WarcFormatException e ) {
			throw new IllegalStateException( WarcHeader.Name.WARC_RECORD_ID + " '" + header.getValue() + "' falied parsing", e );
		}
		if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "Got UUID {}, parsed as {}", header.getValue(), uuid );
		return uuid;
	}

	@Override
	public Type getWarcType() {
		final Header header = WarcHeader.getFirstHeader( this.warcHeaders, WarcHeader.Name.WARC_TYPE );
		if ( header == null ) throw new IllegalStateException( WarcHeader.Name.WARC_TYPE + " mandatory header not present" );
		return Type.valueOf( header );
	}

	@Override
	public Date getWarcDate() {
		final Header header = WarcHeader.getFirstHeader( this.warcHeaders, WarcHeader.Name.WARC_DATE );
		if ( header == null ) throw new IllegalStateException( WarcHeader.Name.WARC_DATE + " mandatory header not present" );
		Date date = null; 
		try {
			date = WarcHeader.parseDate( header.getValue() ); 
		} catch ( WarcFormatException e ) {
			throw new IllegalStateException( WarcHeader.Name.WARC_DATE + " '" + header.getValue() + "' falied parsing", e );
		}
		if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "Got date {}, parsed as {}", header.getValue(), date );
		return date;
	}

	@Override
	public long getWarcContentLength() {
		final Header header = WarcHeader.getFirstHeader( this.warcHeaders, WarcHeader.Name.CONTENT_LENGTH );
		if ( header == null ) throw new IllegalStateException( WarcHeader.Name.CONTENT_LENGTH + " mandatory header not present" );
		return Long.parseLong( header.getValue() );
	}

	@Override
	public URI getWarcTargetURI() {
		final Header header = WarcHeader.getFirstHeader( this.warcHeaders, WarcHeader.Name.WARC_TARGET_URI );
		if ( header == null ) throw new IllegalStateException( WarcHeader.Name.WARC_TARGET_URI + " header not present" );
		return URI.create( header.getValue() );
	}
	
	protected abstract InputStream writePayload( final ByteArraySessionOutputBuffer buffer ) throws IOException;
	
	protected static void writeHeaders( final HeaderGroup headers, final OutputStream output ) throws IOException {
		for ( HeaderIterator it = headers.iterator(); it.hasNext(); ) {
			org.apache.http.Header header = it.nextHeader();
			Util.toOutputStream( BasicLineFormatter.formatHeader( header, null ), output );
			output.write( ByteArraySessionOutputBuffer.CRLF );
		}
	}

	@Override
	public void write( OutputStream output, ByteArraySessionOutputBuffer buffer ) throws IOException {

		buffer.reset();
		InputStream payload = writePayload( buffer );
		long contentLength = buffer.contentLength();

		this.warcHeaders.updateHeader( new WarcHeader( WarcHeader.Name.CONTENT_LENGTH, Long.toString( contentLength ) ) );

		Util.toOutputStream( BasicLineFormatter.formatProtocolVersion( WarcRecord.PROTOCOL_VERSION, null ), output );
		output.write( ByteArraySessionOutputBuffer.CRLF );
		writeHeaders( this.warcHeaders, output );
		output.write( ByteArraySessionOutputBuffer.CRLF );
		ByteStreams.copy( payload, output );
		output.write( ByteArraySessionOutputBuffer.CRLFCRLF );
	}

	public static WarcRecord fromPayload( final HeaderGroup warcHeaders, final BoundSessionInputBuffer payloadBuffer ) throws IOException, WarcFormatException {
		Header warcTypeHeader = WarcHeader.getFirstHeader( warcHeaders, WarcHeader.Name.WARC_TYPE );
		if ( warcTypeHeader == null ) throw new WarcFormatException( "Missing 'WARC-Type' header" );
		Method fromPayloadMethod = null;
		try {
			fromPayloadMethod = WarcRecord.Type.fromPayloadMethod( warcTypeHeader );
		} catch ( IllegalArgumentException e ) {
			throw new WarcFormatException( "Unrecognized record type", e );
		}
		try {
			return (WarcRecord) fromPayloadMethod.invoke( null, warcHeaders, payloadBuffer );
		} catch ( IllegalAccessException e ) {
			throw new RuntimeException( e );
		} catch ( IllegalArgumentException e ) {
			throw new RuntimeException( e );
		} catch ( InvocationTargetException e ) {
			throw new IOException( e );
		}		
	}

}