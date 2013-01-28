package com.vaguehope.s3toad;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.vfs2.FileChangeEvent;
import org.apache.commons.vfs2.FileListener;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.impl.DefaultFileMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.s3.AmazonS3;
import com.vaguehope.s3toad.util.NamedThreadFactory;

public class WatchUpload {

	protected static final Logger LOG = LoggerFactory.getLogger(WatchUpload.class);

	private final AmazonS3 s3Client;
	private final File dir;
	private final String bucket;
	private final ExecutorService controlExecutor;
	private final ExecutorService workerExecutor;
	private final long chunkSize;

	public WatchUpload (AmazonS3 s3Client, File file, String bucket, int workerThreads, int controlThreads, long chunkSize) {
		this.s3Client = s3Client;
		this.dir = file;
		this.bucket = bucket;
		this.controlExecutor = Executors.newFixedThreadPool(controlThreads, new NamedThreadFactory("ctrl"));
		this.workerExecutor = Executors.newFixedThreadPool(workerThreads, new NamedThreadFactory("wrkr"));
		this.chunkSize = chunkSize;
	}

	public void dispose () {
		this.controlExecutor.shutdown();
		this.workerExecutor.shutdown();
	}

	public void run () throws Exception {
		final FileSystemManager fsm = VFS.getManager();
		final FileObject dirObj = fsm.toFileObject(this.dir);

		DefaultFileMonitor fm = new DefaultFileMonitor(new MyFileListener(this));
		fm.setRecursive(true);
		fm.addFile(dirObj);
		fm.start();
		LOG.info("Watch started.");
		new CountDownLatch(1).await();
	}

	protected void fileCreated (FileChangeEvent event) {
		try {
			final FileObject fileObj = event.getFile();
			final File file = new File(fileObj.getURL().getPath()).getCanonicalFile();

			if (file.length() <= 0) {
				LOG.info("Ignoring zero length file: {}", file.getAbsoluteFile());
				return;
			}

			LOG.info("fileCreated={}", file.getAbsolutePath());
			final String key = getRelativePath(this.dir, file);
			LOG.info("fileKey={}", key);

			UploadMulti u = new UploadMulti(this.s3Client, file, this.bucket, key, this.workerExecutor, this.chunkSize);
			UploadCaller c = new UploadCaller(u);
			this.controlExecutor.submit(c);
		}
		catch (Exception e) {
			LOG.error("Failed to sechedule upload for created file: {}", event.getFile(), e);
		}
	}

	private static String getRelativePath (File dir, File file) {
		String base = dir.getAbsolutePath();
		String path = file.getAbsolutePath();
		return path.substring(base.length() + (base.endsWith("/") ? 0 : 1));
	}

	private static class UploadCaller implements Callable<Void> {

		final private UploadMulti u;

		public UploadCaller (UploadMulti u) {
			this.u = u;
		}

		@Override
		public Void call () {
			try {
				this.u.run();
			}
			catch (Exception e) {
				LOG.error("Upload failed.", e);
			}
			return null;
		}

	}

	private static class MyFileListener implements FileListener {

		final private WatchUpload watchUpload;

		public MyFileListener (WatchUpload watchUpload) {
			this.watchUpload = watchUpload;
		}

		@Override
		public void fileCreated (FileChangeEvent event) throws Exception {
			this.watchUpload.fileCreated(event);
		}

		@Override
		public void fileDeleted (FileChangeEvent event) throws Exception {
			// Unused.
		}

		@Override
		public void fileChanged (FileChangeEvent event) throws Exception {
			// Unused.
		}

	}

}