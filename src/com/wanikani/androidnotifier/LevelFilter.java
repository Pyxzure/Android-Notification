package com.wanikani.androidnotifier;

import java.io.IOException;
import java.util.Hashtable;
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
 * A filter that shows all the items belonging to a specific level.
 * This is done through the "Radicals/Kanji/Vocab" WK API, 
 * so all the information should be available.
 * Items are published following these steps:
 * <ul>
 * <li>One single chunk, containing all the radicals that have an unicode character
 * <li>Each radical that has no unicode (one at a time, as they are "resolved")
 * <li>One single chunk, containing all the kanji
 * <li>One single chunk, containing all the vocab words
 * </ul>
 */
public class LevelFilter implements Filter {
	
	/**
	 * The asynchronous task that performs the real job. 
	 */
	private class Task extends AsyncTask<Void, ItemLibrary<Item>, Boolean > {
		
		/// The connection
		Connection conn;
		
		/// The meter
		Connection.Meter meter;

		/// The level to fetch
		int level;
		
		/// List of all the items collected so far
		List<Item> allItems;
		
		/**
		 * Constructor.
		 * @param conn WKLib connection
		 * @param level the level to fetch
		 */
		public Task (Connection.Meter meter, Connection conn, int level)
		{
			this.conn = conn;
			this.meter = meter;
			this.level = level;
			
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
				lib.add (conn.getRadicals (meter, level));
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
			
			for (Radical r : imgrad) {
				try {
					itemf.loadRadicalImage (r);
				} catch (IOException e) {
					r.character = "?";
					ok = false;
				}				
				lpublishProgress (new ItemLibrary<Item> (r));
			}	
			
			lib = new ItemLibrary<Item> ();
			try {
				lib.add (conn.getKanji (meter, level));
				lpublishProgress (lib);
			} catch (IOException e) {
				ok = false;
			}
			
			lib = new ItemLibrary<Item> ();
			try {
				lib.add (conn.getVocabulary (meter, level));
				lpublishProgress (lib);
			} catch (IOException e) {
				ok = false;
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
			done (this, allItems, level, ok);
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

	/// List of pending tasks. In addition to the current task (i.e. the task
	/// that is allowed to push items to the fragment) this hashtable holds
	/// all the other "cancelled" tasks that have not completed yet.
	/// This way, if the fragment becomes interested to one of those at
	/// a later stage, we don't have to create a new one and wait for
	/// the result.
	Hashtable<Integer, Task> pending;

	/// The task currently going on
	Task task;
	
	/**
	 * Constructor.
	 * @param itemf the fragment that will be notified
	 */
	public LevelFilter (Filter.Callback itemf)	
	{
		this.itemf = itemf;
		
		pending = new Hashtable<Integer, Task> ();
	}
	
	/**
	 * Requests information, according to the {@link Filter} pattern.
	 * If data is already available, it is pushed immediately to the fragment.
	 * If a task is alreay running, we wait for it to complete, republishing
	 * the data collected so far.
	 * Otherwise a new task is created, and data will be published as soon
	 * as possible.
	 * @param conn a WKLib Connection
	 * @param level the level
	 */
	public void select (Connection.Meter meter, Connection conn, int level)
	{
		Task ptask;
	
		ptask = pending.get (level);
		
		itemf.enableSorting (true, true, true, true);
		if (ptask == null) {
			itemf.clearData (this);
			itemf.selectLevel (this, level, true);

			task = new Task (meter, conn, level);
			task.execute ();
			pending.put (level, task);
		} else {
			itemf.clearData (this);
			itemf.selectLevel (this, level, true);
			task = ptask;
			task.reissue ();
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
	private void done (Task stask, List<Item> allItems, int level, boolean ok)
	{
		pending.remove (level);
		
		if (stask == task) {
			itemf.noMoreData (this, ok);
			itemf.selectLevel (this, level, false);
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

	/**
	 * Clears the cache. Pending tasks are not cancelled, because
	 * they are providing fresh data anyway.
	 */
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
