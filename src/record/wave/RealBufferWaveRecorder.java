/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014,2015 Dennis Sheirer
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package record.wave;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import module.Module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sample.ConversionUtils;
import sample.Listener;
import sample.real.IRealBufferListener;
import sample.real.RealBuffer;
import util.TimeStamp;
import controller.ThreadPoolManager;
import controller.ThreadPoolManager.ThreadType;

/**
 * WAVE audio recorder module for recording real sample buffers to a wave file
 */
public class RealBufferWaveRecorder extends Module 
				implements IRealBufferListener, Listener<RealBuffer>
{
	private final static Logger mLog = 
			LoggerFactory.getLogger( RealBufferWaveRecorder.class );

    private WaveWriter mWriter;
    private String mFilePrefix;
    private Path mFile;
	private AudioFormat mAudioFormat;
	
	private ThreadPoolManager mThreadPoolManager;
	private BufferProcessor mBufferProcessor;
	private ScheduledFuture<?> mProcessorHandle;
	private LinkedBlockingQueue<RealBuffer> mBuffers = 
			new LinkedBlockingQueue<>( 500 );
	private long mLastBufferReceived;
	
	private AtomicBoolean mRunning = new AtomicBoolean();
	
	public RealBufferWaveRecorder( ThreadPoolManager threadPoolManager, 
			int sampleRate, String filePrefix )
	{
		mThreadPoolManager = threadPoolManager;
		
		mAudioFormat = 	new AudioFormat( sampleRate,  //SampleRate
										 16,     //Sample Size
										 1,      //Channels
										 true,   //Signed
										 false ); //Little Endian

		mFilePrefix = filePrefix;
	}

	/**
	 * Timestamp of when the latest buffer was received by this recorder
	 */
	public long getLastBufferReceived()
	{
		return mLastBufferReceived;
	}

	public Path getFile()
	{
		return mFile;
	}
	
	public void start()
	{
		if( mRunning.compareAndSet( false, true ) )
		{
			if( mBufferProcessor == null )
			{
				mBufferProcessor = new BufferProcessor();
			}

			try
			{
				StringBuilder sb = new StringBuilder();
				sb.append( mFilePrefix );
				sb.append( "_" );
				sb.append( TimeStamp.getTimeStamp( "_" ) );
				sb.append( ".wav" );
				
				mFile = Paths.get( sb.toString() );

				mWriter = new WaveWriter( mAudioFormat, mFile );

				/* Schedule the processor to run every 500 milliseconds */
				mProcessorHandle = mThreadPoolManager.scheduleFixedRate( 
					ThreadType.AUDIO_RECORDING, mBufferProcessor, 500, 
					TimeUnit.MILLISECONDS );
			}
			catch( IOException io )
			{
				mLog.error( "Error starting real buffer recorder", io );
			}
		}
	}
	
	public void stop()
	{
		if( mRunning.compareAndSet( true, false ) )
		{
			mThreadPoolManager.cancel( mProcessorHandle );
			
			mProcessorHandle = null;
			
			if( mWriter != null )
			{
				try
				{
					/* Flush remaining buffers */
					writeAudio();
					mWriter.close();
				}
				catch( IOException io )
				{
					mLog.error( "Error stopping real wave recorder [" + 
								getFile() + "]", io );
				}
			}

		}
		
		mBuffers.clear();
		mWriter = null;
		mFile = null;
	}
	
	@Override
    public void receive( RealBuffer buffer )
    {
		if( mRunning.get() )
		{
			boolean success = mBuffers.offer( buffer );
			
			if( !success )
			{
				mLog.error( "recorder buffer overflow or error - throwing "
						+ "away a buffer of sample data" );
				stop();
			}
			
			mLastBufferReceived = System.currentTimeMillis();
		}
    }
	
	@Override
	public Listener<RealBuffer> getRealBufferListener()
	{
		return this;
	}

	@Override
	public void dispose()
	{
		stop();
		
		mThreadPoolManager = null;
	}

	@Override
	public void reset()
	{
	}
	
	private void writeAudio() throws IOException
	{
		RealBuffer buffer = mBuffers.poll();
		
		while( buffer != null )
		{
			mWriter.write( ConversionUtils.convertToSigned16BitSamples( buffer ) );
			buffer = mBuffers.poll();
		}
	}
	
	public class BufferProcessor implements Runnable
    {
    	public void run()
    	{
			try
            {
				writeAudio();
            }
			catch ( IOException ioe )
			{
				/* Stop this module if/when we get an IO exception */
				stop();
				
				mLog.error( "IOException while trying to write to the wave "
						+ "writer", ioe );
			}
    	}
    }
}
