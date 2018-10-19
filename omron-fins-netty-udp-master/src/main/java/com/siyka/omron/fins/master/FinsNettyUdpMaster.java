package com.siyka.omron.fins.master;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.siyka.omron.fins.FinsFrame;
import com.siyka.omron.fins.FinsHeader;
import com.siyka.omron.fins.FinsIoAddress;
import com.siyka.omron.fins.FinsNodeAddress;
import com.siyka.omron.fins.FinsPdu;
import com.siyka.omron.fins.codec.FinsCommandFrameEncoder;
import com.siyka.omron.fins.codec.FinsFrameUdpCodec;
import com.siyka.omron.fins.codec.FinsResponseFrameDecoder;
import com.siyka.omron.fins.commands.FinsCommand;
import com.siyka.omron.fins.responses.FinsResponse;
import com.siyka.omron.fins.wip.MemoryAreaReadCommand;
import com.siyka.omron.fins.wip.MemoryAreaReadWordResponse;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class FinsNettyUdpMaster implements FinsMaster {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private final FinsNodeAddress nodeAddress;
	private final InetSocketAddress destinationAddress;
	private final InetSocketAddress sourceAddress;

	private final NioEventLoopGroup workerGroup;
	private final Bootstrap bootstrap;
	private Channel channel;

	private final AtomicInteger serviceAddress = new AtomicInteger(0);

	private final Map<Byte, CompletableFuture<FinsFrame<FinsResponse>>> futures;

	// TODO make configurable
//	private int retries = 3;

	public FinsNettyUdpMaster(final InetSocketAddress destinationAddress, final InetSocketAddress sourceAddress, final FinsNodeAddress nodeAddress) {
		this.nodeAddress = nodeAddress;
		this.sourceAddress = sourceAddress;
		this.destinationAddress = destinationAddress;

		this.futures = new HashMap<>();

		this.workerGroup = new NioEventLoopGroup();
		this.bootstrap = new Bootstrap();
		this.bootstrap.group(this.workerGroup)
				.channel(NioDatagramChannel.class)
				.option(ChannelOption.SO_BROADCAST, true)
				.handler(new ChannelInitializer<DatagramChannel>() {
					@Override
					public void initChannel(DatagramChannel channel) throws Exception {
						channel.pipeline()
								.addLast(new LoggingHandler(LogLevel.DEBUG))
								.addLast(new FinsFrameUdpCodec<>(new FinsCommandFrameEncoder(), new FinsResponseFrameDecoder()))
								.addLast(new FinsMasterHandler(FinsNettyUdpMaster.this.futures));
					}
				});
	}

	// FINS Master API
	@Override
	public CompletableFuture<Void> connect() {
		final CompletableFuture<Void> connectFuture = new CompletableFuture<>();
		this.bootstrap.connect(this.destinationAddress, this.sourceAddress).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				FinsNettyUdpMaster.this.channel = future.sync().channel();
				connectFuture.complete(null);
			}
		});
		return connectFuture;
	}

	@Override
	public CompletableFuture<Void> disconnect() {
		final CompletableFuture<Void> disconnectFuture = new CompletableFuture<>();
		this.workerGroup.shutdownGracefully().addListener(f -> {
			f.sync();
			disconnectFuture.complete(null);
		});
		return disconnectFuture;
	}
	
	@Override
	public CompletableFuture<List<Short>> readWords(final FinsNodeAddress destination, final FinsIoAddress address, final short itemCount) {

		final FinsHeader header = FinsHeader.Builder.defaultCommandBuilder()
				.setDestinationAddress(destination)
				.setSourceAddress(this.nodeAddress)
				.setServiceAddress(this.getNextServiceAddress())
				.build();
		
		// TODO Check to make sure the address space is for WORD data
		
		final MemoryAreaReadCommand command = new MemoryAreaReadCommand(address, itemCount);
		final FinsFrame<MemoryAreaReadCommand> frame = new FinsFrame<>(header, command);

		return this.send(frame)
				.thenApply(responseFrame -> {
					final FinsPdu response = responseFrame.getPdu();
					
					if (response instanceof MemoryAreaReadWordResponse) {
						return ((MemoryAreaReadWordResponse) response).getItems();
					}
					
					return Collections.emptyList();
//					if (response.getEndCode() != FinsEndCode.NORMAL_COMPLETION) {
//						throw new FinsMasterException(String.format("%s", response.getEndCode()));
//					}
				});
	}

	@Override
	public CompletableFuture<List<Short>> readWords(final FinsNodeAddress destination, final FinsIoAddress address, final int itemCount) {
		return readWords(destination, address, (short) itemCount);
	}

//	@Override
//	public CompletableFuture<Short> readWord(final FinsNodeAddress destination, final FinsIoAddress address) {
//		return readWords(destination, address, 1).handleAsync((words, throwable) -> words.get(0));
//	}

//	@Override
//	public CompletableFuture<String> readString(final FinsNodeAddress destination, final FinsIoAddress address, final short wordLength) {
//		return this.readWords(destination, address, wordLength).handleAsync((words, throwable) -> {
//			StringBuffer stringBuffer = new StringBuffer(wordLength * 2);
//			byte[] bytes = new byte[2];
//			for (Short s : words) {
//				bytes[1] = (byte) (s & 0xff);
//				bytes[0] = (byte) ((s >> 8) & 0xff);
//				try {
//					stringBuffer.append(new String(bytes, "US-ASCII"));
//				} catch (UnsupportedEncodingException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
//
//			return stringBuffer.toString();
//		});
//	}

//	@Override
//	public CompletableFuture<String> readString(final FinsNodeAddress destination, final FinsIoAddress address, final int wordLength) {
//		return readString(destination, address, (short) wordLength);
//	}
	
//	@Override
//	public CompletableFuture<List<Bit>> readBits(final FinsNodeAddress destination, final FinsIoAddress address, final short itemCount) {
//		MemoryAreaReadCommand command = new MemoryAreaReadCommand(address, itemCount);
//
//		FinsFrame frame = new FinsFrameBuilder().setDestinationAddress(destination).setSourceAddress(this.nodeAddress)
//				.setServiceAddress(this.getNextServiceAddress()).setData(command.getBytes()).build();
//
//		FinsFrame replyFrame = this.send(frame);
//		byte[] data = replyFrame.getData();
//		MemoryAreaReadBitResponse response = MemoryAreaReadBitResponse.parseFrom(data, itemCount);
//		List<Bit> items = response.getItems();
//
//		if (response.getEndCode() != FinsEndCode.NORMAL_COMPLETION) {
//			throw new FinsMasterException(String.format("%s", response.getEndCode()));
//		}
//
//		return items;
//	}

//	@Override
//	public CompletableFuture<List<Bit>> readBits(final FinsNodeAddress destination, final FinsIoAddress address, final int itemCount) {
//		return readBits(destination, address, (short) itemCount);
//	}

//	@Override
//	public CompletableFuture<Bit> readBit(final FinsNodeAddress destination, final FinsIoAddress address) {
//		return readBits(destination, address, 1).handleAsync((bits, throwable) -> bits.get(0));
//	}

//	@Override
//	public CompletableFuture<List<Short>> readMultipleWords(final FinsNodeAddress destination, final List<FinsIoAddress> addresses) {
//		// TODO Auto-generated method stub
//		throw new UnsupportedOperationException("Not implemented yet");
//	}

//	@Override
//	public CompletableFuture<Void> writeWords(final FinsNodeAddress destination, final FinsIoAddress address, final List<Short> items) {
//		MemoryAreaWriteWordCommand command = new MemoryAreaWriteWordCommand(address, items);
//
//		FinsFrame frame = new FinsFrameBuilder().setDestinationAddress(destination).setSourceAddress(this.nodeAddress)
//				.setServiceAddress(this.getNextServiceAddress()).setData(command.getBytes()).build();
//
//		FinsFrame replyFrame = this.send(frame);
//		MemoryAreaWriteResponse response = MemoryAreaWriteResponse.parseFrom(replyFrame.getData());
//
//		if (response.getEndCode() != FinsEndCode.NORMAL_COMPLETION) {
//			throw new FinsMasterException(String.format("%s", response.getEndCode()));
//		}
//	}

//	@Override
//	public CompletableFuture<Void> writeWord(final FinsNodeAddress destination, final FinsIoAddress address, final short value) {
//		List<Short> items = new ArrayList<Short>();
//		items.add(value);
//		writeWords(destination, address, items);
//	}

//	public CompletableFuture<Void> writeBit(final FinsNodeAddress destination, final FinsIoAddress address, final Boolean value) {
//		MemoryAreaWriteBitCommand command = new MemoryAreaWriteBitCommand(address, value);
//
//		FinsFrame frame = new FinsFrameBuilder().setDestinationAddress(destination).setSourceAddress(this.nodeAddress)
//				.setServiceAddress(this.getNextServiceAddress()).setData(command.getBytes()).build();
//
//		FinsFrame replyFrame = this.send(frame);
//		MemoryAreaWriteResponse response = MemoryAreaWriteResponse.parseFrom(replyFrame.getData());
//
//		if (response.getEndCode() != FinsEndCode.NORMAL_COMPLETION) {
//			throw new FinsMasterException(String.format("%s", response.getEndCode()));
//		}
//
//	}

//	@Override
//	public CompletableFuture<Void> writeMultipleWords(final FinsNodeAddress destination, final List<FinsIoAddress> addresses, final List<Short> values) {
//		// TODO Auto-generated method stub
//		throw new UnsupportedOperationException("Not implemented yet");
//	}

	// Internal methods
	private <T extends FinsCommand> CompletableFuture<FinsFrame<FinsResponse>> send(final FinsFrame<? extends FinsCommand> frame, final int attempt) {
		logger.debug("Sending FinsFrame");
		final CompletableFuture<FinsFrame<FinsResponse>> future = new CompletableFuture<>();
		logger.debug("Storing response future with service ID {}", frame.getHeader().getServiceAddress());
		this.futures.put(frame.getHeader().getServiceAddress(), future);
		logger.debug("Writing and flushing FinsFrame");
		logger.debug("Channel {} Active:{} Writable:{} Open:{} Registered:{}", this.channel, this.channel.isActive(), this.channel.isWritable(), this.channel.isOpen(), this.channel.isRegistered());

		try {
			this.channel.writeAndFlush(frame).sync();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		logger.debug("Returning response future");

		return future;
//		try {
//
//			logger.debug("Write and flush FinsFrame");
//
//			logger.debug("Awaiting future to be completed");
//			FinsFrame replyFrame = this.sendFuture.get(1000, TimeUnit.MILLISECONDS);
//			logger.debug("Future compeleted");
//			return replyFrame;
//		} catch (TimeoutException e) {
//			if (attempt < this.retries) {
//				return send(frame, attempt++);
//			} else {
//				return null;
//			}
//		} catch (InterruptedException | ExecutionException e) {
//			return null;
//		}
	}

	private CompletableFuture<FinsFrame<FinsResponse>> send(final FinsFrame<? extends FinsCommand> frame) {
		return this.send(frame, 0);
	}

	private synchronized byte getNextServiceAddress() {
		return (byte) this.serviceAddress.incrementAndGet();
	}

}