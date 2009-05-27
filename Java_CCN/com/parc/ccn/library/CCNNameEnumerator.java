package com.parc.ccn.library;

import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.LinkedList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;



/**
 * Implements the base Name Enumerator.  Applications register name prefixes.
 * Each prefix is explored until canceled by the application.
 * 
 * An application can have multiple enumerations active at the same time.
 * For each prefix, the name enumerator will generate an Interest.  Responses
 * to the Interest will be in the form of Collections (by a
 * NameEnumeratorResponder and repository implementations).  Returned Collections
 * will be parsed for the enumerated names and sent back to the application
 * using the callback with the applicable prefix and an array of names in
 * that namespace.  The application is expected to handle duplicate names from
 * multiple responses and should be able to handle names that are returned, but
 * may not be available at this time (for example, /a.com/b/c.txt might have
 * been enumerated but a.com content may not be available).  
 * 
 * @author rbraynar
 *
 */

public class CCNNameEnumerator implements CCNFilterListener, CCNInterestListener {

	public static final byte NAME_ENUMERATION_MARKER = (byte)0xFE;
	public static final byte [] NEMARKER = new byte []{NAME_ENUMERATION_MARKER};
	
	protected CCNLibrary _library = null;
	//protected ArrayList<ContentName> _registeredPrefixes = new ArrayList<ContentName>();
	protected BasicNameEnumeratorListener callback; 
	protected ArrayList<ContentName> _registeredNames = new ArrayList<ContentName>();
	
	private class NERequest{
		ContentName prefix = null;
		ArrayList<Interest> ongoingInterests = new ArrayList<Interest>();
		
		public NERequest(ContentName n) {
			prefix = n;
		}
		
		Interest getInterest(ContentName in) {
			for (Interest i : ongoingInterests)
				if (i.name().equals(in))
					return i;
			return null;
		}
		
		void removeInterest(Interest i) {
			ongoingInterests.remove(getInterest(i.name()));
		}
		
		void addInterest(Interest i) {
			if (getInterest(i.name()) == null)
				ongoingInterests.add(i);
		}
		
		ArrayList<Interest> getInterests() {
			return ongoingInterests;
		}
		
	}
	
	
	private class NEResponse {
		ContentName prefix = null;
		boolean dirty = true;
		
		public NEResponse(ContentName n) {
			prefix = n;
		}
		
		boolean isDirty() {
			return dirty;
		}
		
		void clean() {
			dirty = false;
		}
		
		void dirty() {
			dirty = true;
		}
	}
	
	protected ArrayList<NEResponse> _handledResponses = new ArrayList<NEResponse>();
	protected ArrayList<NERequest>  _currentRequests = new ArrayList<NERequest>();
	
	public CCNNameEnumerator(ContentName prefix, CCNLibrary library, BasicNameEnumeratorListener c) throws IOException {
		_library = library;
		callback = c;
		registerPrefix(prefix);
	}
	
	public CCNNameEnumerator(CCNLibrary library, BasicNameEnumeratorListener c) {
		_library = library;
		callback = c;
	}
	
	public void registerPrefix(ContentName prefix) throws IOException{
		NERequest r = getCurrentRequest(prefix);
		if (r == null) {
			r = new NERequest(prefix);
			_currentRequests.add(r);
		}
			
		//Library.logger().info("Registered Prefix");
		//Library.logger().info("creating Interest");
			
		ContentName prefixMarked = new ContentName(prefix, NEMARKER);
			
		Interest pi = new Interest(prefixMarked);
		pi.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME);
		pi.nameComponentCount(prefix.count() + 1);
			
		//Library.logger().info("interest name: "+pi.name().toString()+" prefix: "+pi.name().prefixCount()+" order preference "+pi.orderPreference());
		r.addInterest(pi);
		
		_library.expressInterest(pi, this);
			
		//Library.logger().info("expressed Interest: "+prefixMarked.toString());
	}
	
	
	public boolean cancelPrefix(ContentName prefix) {
		//cancel the behind the scenes interests and remove from the local ArrayList
		NERequest r = getCurrentRequest(prefix);
		if (r != null) {
			ArrayList<Interest> is = r.getInterests();
			for (Interest i: is)
				_library.cancelInterest(i, this);
			
			_currentRequests.remove(r);
			return (getCurrentRequest(prefix) == null);
		}

		return false;
	}
	
	
	/*public ArrayList<ContentName> parseCollection(Collection c) {
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		
		// TODO fill in Collection to Names translation....
		// can we just get the body of the collection object to avoid a copy?
		
		return names;
	}
	*/
	
	
	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
		
		//Library.logger().info("we recieved a Collection matching our prefix...");
		
		if (interest.name().contains(NEMARKER)) {
			//the NEMarker is in the name...  good!
		} else {
			//NEMARKER missing...  we have a problem
			System.err.println("the name enumeration marker is missing...  shouldn't have gotten this callback");
			_library.cancelInterest(interest, this);
			return null;
		}
		Collection collection;
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		LinkedList<LinkReference> links;
		ContentName responseName = null;
		
		//TODO  integrate handling for multiple responders, for now, just handles one result properly
		if (results != null) {
			for (ContentObject c: results) {
				//Library.logger().info("we have a match on "+interest.name());
				//responseName = c.name();
				responseName = new ContentName(c.name(), c.contentDigest());
				
				try {
					collection = Collection.contentToCollection(c);
					links = collection.contents();
					for (LinkReference l: links) {
						names.add(l.targetName());
						//Library.logger().info("names: "+l.targetName());
					}
					//strip off NEMarker before passing through callback
					callback.handleNameEnumerator(interest.name().cut(NEMARKER), names);
				} catch(XMLStreamException e) {
					e.printStackTrace();
					System.err.println("Error getting CollectionData from ContentObject in CCNNameEnumerator");
				}		
			}
		}
		Interest newInterest = interest;
		if (responseName != null) {
			newInterest = Interest.last(responseName);
			//newInterest.orderPreference(newInterest.name().count()-2);
			newInterest.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME);// | Interest.ORDER_PREFERENCE_RIGHT);
			newInterest.nameComponentCount(interest.nameComponentCount());
			NERequest ner = getCurrentRequest(interest.name().cut(NEMARKER));
			if (ner != null) {
				ner.removeInterest(interest);
				ner.addInterest(newInterest);
			}
			/*
			 Library.logger().info("new interest name: "+newInterest.name()+" total components: "+newInterest.name().count());
			 
			  try {
			  	Library.logger().info("version: "+VersioningProfile.getVersionAsTimestamp(responseName));
			  }
			  catch(Exception e) {}
			*/
		}
		return newInterest;
	}
	
	// temporary workaround to test the callback without actually processing ContentObjects
	
	public int handleContent(ArrayList<ContentName> results, ContentName p) {
		
		//Library.logger().info("we recieved content matching our prefix...");
		
		//Need to make sure the response has the NEMarker in it
		if (!p.contains(NEMARKER)) {
			System.err.println("something is wrong...  we should have had the Name Enumeration Marker in the name");
		} else {
			//Library.logger().info("we have a match on "+p.toString()+" and the NEMarker is in there!");
			NERequest r = getCurrentRequest(p);
			if (r != null) {
				callback.handleNameEnumerator(p, results);
			}
		}
		return results.size();
	}
	
	
	public int handleInterests(ArrayList<Interest> interests) {
		//Library.logger().info("Received Interests matching my filter!");
		
		ContentName collectionName = null;
		LinkReference match;
		CollectionData cd;
				
		
		ContentName name = null;
		NEResponse r = null;
		for (Interest i: interests) {
			name = i.name().clone();
			//Library.logger().info("processing interest: "+name.toString());
			//collectionName = i.name().clone();
			
			cd = new CollectionData();
			//Verify NameEnumeration Marker is in the name
			if (!name.contains(NEMARKER)) {
				//Skip...  we don't handle these
			} else {
				//Library.logger().info("this interest contains the NE marker!");
				name = name.cut(NEMARKER);
				collectionName = new ContentName(name, NEMARKER);
				
				
				boolean skip = false;
				//have we handled this response already?
				r = getHandledResponse(name);
				if (r != null) {
					//we have handled this before!
					if (r.isDirty()) {
						//this has updates to send back!!
						//Library.logger().info("the marker is dirty!  we have new names to send back!");
					} else {
						//nothing new to send back...  go ahead and skip to next interest
						skip = true;
						//Library.logger().info("no new names to report...  skipping");
					}
				} else {
					//this is a new one...
					//Library.logger().info("adding new handled response: "+name.toString());
					r = new NEResponse(name);
					_handledResponses.add(r);
				}
				if (!skip) {
					for (ContentName n: _registeredNames) {
						//Library.logger().info("checking registered name: "+n.toString());
						if (name.isPrefixOf(n)) {
							ContentName tempName = n.clone();
							//Library.logger().info("we have a match! ("+tempName.toString()+")");
							//Library.logger().info("prefix size "+name.count()+" registered name size "+n.count());
							byte[] tn = n.component(name.count());
							byte[][] na = new byte[1][tn.length];
							na[0] = tn;
							tempName = new ContentName(na);
							match = new LinkReference(tempName);
							//names.add(match);
							if (!cd.contents().contains(match)) {
								cd.add(match);
								//Library.logger().info("added name to response: "+tempName);
							}
						}
					}
				}
			}
			
			if (cd.size()>0) {
				//Library.logger().info("we have a response to send back for "+i.name().toString());
				//Library.logger().info("Collection Name: "+collectionName.toString());
				try {
					
					//the following 6 lines are to be deleted after Collections are refactored
					LinkReference[] temp = new LinkReference[cd.contents().size()];
					for (int x = 0; x < cd.contents().size(); x++)
						temp[x] = cd.contents().get(x);
					_library.put(collectionName, temp);
					
					//CCNEncodableCollectionData ecd = new CCNEncodableCollectionData(collectionName, cd);
					//ecd.save();
					//Library.logger().info("saved ecd.  name: "+ecd.getName());
					r.clean();

				} catch(IOException e) {
					
				} catch(SignatureException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				//Library.logger().info("this interest did not have any matching names...  not returning anything.");
				if (r != null)
					r.clean();
			}
		}
			
		return 0;
	}

	public boolean containsRegisteredName(ContentName name) {
		if (name == null) {
			System.err.println("trying to check for null registered name");
			return false;
		}
		if (_registeredNames.contains(name))
			return true;
		else
			return false;
	}
	
	public void registerNameSpace(ContentName name) {
		
		if (!_registeredNames.contains(name)) {
			_registeredNames.add(name);
			//Library.logger().info("registered "+ name.toString()+" as namespace");
			_library.registerFilter(name, this);
		}
		
	}
	
	public void registerNameForResponses(ContentName name) {

		if (name == null) {
			System.err.println("The content name for registerNameForResponses was null, ignoring");
			return;
		}
		//Do not need to register each name as a filter...  the namespace should cover it
		//_library.registerFilter(name, this);
		if (!_registeredNames.contains(name)) {
			// DKS - if we don't care about order, could use a Set instead of an ArrayList,
			// then just call add as duplicates suppressed
		  _registeredNames.add(name);
		  //Library.logger().info("registered "+ name.toString()+") for responses");		  
		}
		
		//check prefixes that were handled...  if so, mark them dirty
		updateHandledResponses(name);
	}
	
	protected NEResponse getHandledResponse(ContentName n) {
		//Library.logger().info("checking handled responses...");
		for (NEResponse t: _handledResponses) {
			//Library.logger().info("getHandledResponse: "+t.prefix.toString());
			if (t.prefix.equals(n))
				return t;
		}
		return null;
	}
	
	protected void updateHandledResponses(ContentName n) {
		for (NEResponse t: _handledResponses) {
			if (t.prefix.isPrefixOf(n)) {
				t.dirty();
			}
		}
	}
	
	protected NERequest getCurrentRequest(ContentName n) {
		//Library.logger().info("checking current requests...");
		for (NERequest r: _currentRequests) {
			if (r.prefix.equals(n))
				return r;
		}
		return null;
	}
	
}
