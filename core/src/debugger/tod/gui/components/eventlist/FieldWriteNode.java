/*
TOD - Trace Oriented Debugger.
Copyright (c) 2006-2008, Guillaume Pothier
All rights reserved.

This program is free software; you can redistribute it and/or 
modify it under the terms of the GNU General Public License 
version 2 as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, 
but WITHOUT ANY WARRANTY; without even the implied warranty of 
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
General Public License for more details.

You should have received a copy of the GNU General Public License 
along with this program; if not, write to the Free Software 
Foundation, Inc., 59 Temple Place, Suite 330, Boston, 
MA 02111-1307 USA

Parts of this work rely on the MD5 algorithm "derived from the 
RSA Data Security, Inc. MD5 Message-Digest Algorithm".
*/
package tod.gui.components.eventlist;

import tod.core.database.event.IBehaviorCallEvent;
import tod.core.database.event.IFieldWriteEvent;
import tod.core.database.event.ILogEvent;
import tod.gui.Hyperlinks;
import tod.gui.IGUIManager;
import tod.gui.kit.html.HtmlBody;

public class FieldWriteNode extends AbstractSimpleEventNode
{
	private IFieldWriteEvent itsEvent;

	public FieldWriteNode(
			IGUIManager aGUIManager, 
			EventListPanel aListPanel,
			IFieldWriteEvent aEvent)
	{
		super(aGUIManager, aListPanel);
		itsEvent = aEvent;
		createUI();
	}
	
	@Override
	protected void createHtmlUI(HtmlBody aBody)
	{
		Object theCurrentObject = null;
		IBehaviorCallEvent theContainer = itsEvent.getParent();
		if (theContainer != null)
		{
			theCurrentObject = theContainer.getTarget();
		}
		
		aBody.add(Hyperlinks.object(
				getGUIManager(),
				Hyperlinks.HTML,
				getJobScheduler(),
				theCurrentObject, 
				itsEvent.getTarget(),
				itsEvent,
				showPackageNames()));
		
		aBody.addText(".");
		aBody.addText(itsEvent.getField().getName());
		aBody.addText(" = ");
		
		aBody.add(Hyperlinks.object(
				getGUIManager(),
				Hyperlinks.HTML,
				getJobScheduler(),
				theCurrentObject,
				itsEvent.getValue(),
				itsEvent,
				showPackageNames()));

		createDebugInfo(aBody);
	}
	
	@Override
	protected ILogEvent getEvent()
	{
		return itsEvent;
	}
}
