package com.wanikani.androidnotifier;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import android.os.AsyncTask;

import com.wanikani.wklib.Connection;
import com.wanikani.wklib.Item;
import com.wanikani.wklib.ItemLibrary;
import com.wanikani.wklib.Radical;

/* 
 *  Copyright (c) 2013 Alberto Cuda
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * A filter that shows all the items up to the user's level.
 * This is done through the "Radicals/Kanji/Vocab" WK API, 
 * so all the information should be available.
 */
public class NoFilter implements Filter {

	/**
	 * The asynchronous task that performs the real job. 
	 */
	private class Task extends AsyncTask<Void, ItemLibrary<Item>, Boolean > {
		
		/// The connection
		Connection conn;
		
		/// The meter
		Connection.Meter meter;

		/// List of all the items collected so far
		List<Item> allItems;
		
		/**
		 * Constructor.
		 * @param conn WKLib connection
		 */
		public Task (Connection.Meter meter, Connection conn)
		{
			this.conn = conn;
			this.meter = meter;
			
			allItems = new Vector<Item> ();
		}
		
		/**
		 * The method that performs the actual work. We invoke the WK api,
		 * and push each item as soon as possible. 
		 * @param true if everything goes smoothly
		 */
		@Override
		protected Boolean doInBackground (Void... v)
		{
			ItemLibrary<Item> lib;
			List<Radical> imgrad;
			Radical rad;
			Iterator<Item> i;
			boolean ok;
			
			ok = true;
			lib = new ItemLibrary<Item> ();
			imgrad = new Vector<Radical> ();
			try {
				lib.add (conn.getRadicals (meter));
				i = lib.list.iterator ();
				while (i.hasNext ()) {
					rad = (Radical) i.next ();
					if (rad.character == null) {
						imgrad.add (rad);
						i.remove ();
					}
				}
				lpublishProgress (new ItemLibrary<Item> ().add (lib));
			} catch (IOException e) {
				ok = false;
			}
			
			lib = new ItemLibrary<Item> ();
			try {
				lib.add (conn.getKanji (meter));
				lpublishProgress (lib);
			} catch (IOException e) {
				ok = false;
			}
			
			lib = new ItemLibrary<Item> ();
			try {
				lib.add (conn.getVocabulary (meter));
				lpublishProgress (lib);
			} catch (IOException e) {
				ok = false;
			}			

			/* Moved at the end, because I want all the regular items to be
			 * shown as soon as possible (img radicals can be quite a few) */
			for (Radical r : imgrad) {
				try {
					itemf.loadRadicalImage (r);
				} catch (IOException e) {
					r.character = "?";
					ok = false;
				}				
				lpublishProgress (new ItemLibrary<Item> (r));
			}	
			
			return ok;
		}	
		
		/**
		 * Publishes a new library. This method is essentially equivalent
		 * to {@link AsyncTask#publishProgress} but it masks the variadic/generic 
		 * clash warning.
		 * @param lib the library to publish
		 */
		@SuppressWarnings("unchecked")
		protected void lpublishProgress (ItemLibrary<Item> lib)
		{
			publishProgress (lib);
		}

		/**
		 * Called when some new item becomes available. We inform the GUI
		 * and add them to @link {@link #allItems}.
		 * @param lib the new items
		 */		
		@Override
		protected void onProgressUpdate (ItemLibrary<Item>... lib)
		{
			allItems.addAll (lib [0].list);
			update (this, lib [0].list);
		}
						
		/**
		 * Informs the GUI that no more items are expected
		 * @param ok if everything went smoothly
		 */
		@Override
		protected void onPostExecute (Boolean ok)
		{
			done (this, allItems, ok);
		}

		/**
		 * Called when the fragment requests the information again
		 * <i>and</i> this instance has not completed its task yet.
		 * This happens if the user has switched from this filter to
		 * another one, and then came back here. We need to
		 * republish the data collected so far.
		 */
		public void reissue ()
		{
			update (this, allItems);
		}
	}

	/// The fragment which will receive updates
	Filter.Callback itemf;
	
	/// The task that performs the real job, or <code>null</code> if idle
	Task task;
	
	/**
	 * Constructor.
	 * @param itemf the fragment that will be notified
	 */
	public NoFilter (Filter.Callback itemf)	
	{
		this.itemf = itemf;
	}
	
	/**
	 * Requests information, according to the {@link Filter} pattern.
	 * If data is already available, it is pushed immediately to the fragment.
	 * If a task is alreay running, we wait for it to complete, republishing
	 * the data collected so far.
	 * Otherwise a new task is created, and data will be published as soon
	 * as possible.
	 * @param conn a WKLib Connection 
	 */
	public void select (Connection.Meter meter, Connection conn)
	{
		itemf.enableSorting (true, true, true, true);
		
		if (task != null) {
			itemf.clearData (this);
			itemf.selectOtherFilter (this, true);			
			task.reissue ();
		} else {
			itemf.clearData (this);
			itemf.selectOtherFilter (this, true);			

			task = new Task (meter, conn);
			task.execute ();
		} 
	}
	
	/**
	 * Called by the task when some new data becomes available.
	 * @param stask the source task
	 * @param items the new items
	 */
	private void update (Task stask, List<Item> items)
	{
		if (stask == task)
			itemf.addData (this, items);
	}
	
	/**
	 * Called by the task when no more data is available.
	 * We store the list of items into the cache and stop the spinner.
	 * @param stask the source task
	 * @param items all the itmes published
	 * @param ok set if everything went smoothly
	 */
	private void done (Task stask, List<Item> allItems, boolean ok)
	{		
		if (stask == task) {
			task = null;
			itemf.noMoreData (this, ok);
			itemf.selectOtherFilter (this, false);
		}
	}
	
	/**
	 * Called when the fragment does not want to be notified of
	 * items any more. This does not cancel possible pending tasks,
	 * but it simply makes its callbacks ineffective.
	 */
	public boolean stopTask ()
	{
		if (task != null) {
			task = null;
			return true;
		} else
			return false;
	}
	
	@Override
	public void flush ()
	{
		/* empty */
	}
	
	@Override
	public boolean hasSRSLevelInfo ()
	{
		return true;
	}
}
