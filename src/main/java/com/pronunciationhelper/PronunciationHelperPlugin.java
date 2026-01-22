package com.pronunciationhelper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.menus.WidgetMenuOption;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
		name = "Pronunciation Helper"
)
public class PronunciationHelperPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private PronunciationHelperConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MenuManager menuManager;

	private String lastTextProcessed;
	private boolean showOnlyTranslation = false;
	private String originalText;
	private boolean weModifiedText = false;

	private final String MENU_ENTRY_NAME = "Pronounce";

	private boolean keyHeld = false;

	private final KeyListener translationKeyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e) { }

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (config.pronunciationHotkey().matches(e) && !keyHeld)
			{
				weModifiedText = true;
				keyHeld = true;
				showOnlyTranslation = true;
				//allow the sub to take place
				lastTextProcessed = "";
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			if (config.pronunciationHotkey().matches(e))
			{
				weModifiedText = true;
				keyHeld = false;
				showOnlyTranslation = false;
				lastTextProcessed = "";
			}
		}
	};

	@Override
	protected void startUp()
	{
		log.debug("Pronunciation Helper started!");
		keyManager.registerKeyListener(translationKeyListener);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(translationKeyListener);
		log.debug("Pronunciation Helper stopped!");
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		handleDialogueWidget(client.getWidget(ComponentID.DIALOG_NPC_TEXT));
		handleDialogueWidget(client.getWidget(ComponentID.DIALOG_PLAYER_TEXT));
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.showContextMenu()){
			return;
		}
		MenuEntry[] entries = event.getMenuEntries();
		if (entries.length == 0)
			return;

		if (Arrays.stream(entries)
				.anyMatch(e -> MENU_ENTRY_NAME.equals(e.getOption())))
			return;

		for (MenuEntry entry : entries)
		{
			//remove colour formatting as well as combat levels
			String targetName = entry.getTarget()
					.replaceAll("<[^>]*>", "")
					.replaceAll("\\s*\\(level-\\d+\\)$", "");

			String matchedKey = PronunciationHelperDictionary.PRONUNCIATIONS.keySet().stream()
					.filter(word -> targetName.toLowerCase().contains(word.toLowerCase()))
					.findFirst()
					.orElse(null);

			if (matchedKey == null)
				continue;

			//find the cancel entry index
			int cancelIndex = -1;
			for (int i = 0; i < entries.length; i++)
			{
				if (MenuAction.CANCEL.equals(entries[i].getType()))
				{
					cancelIndex = i;
					break;
				}
			}

			//insert above Cancel, or at bottom if no cancel found
			int insertIndex = cancelIndex > 0 ? cancelIndex : 1;

			client.createMenuEntry(insertIndex)
					.setOption(MENU_ENTRY_NAME)
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE_LOW_PRIORITY)
					.onClick(ev -> generatePronunciationChatboxMessage(matchedKey));

			return;
		}
	}


	private void generatePronunciationChatboxMessage(String npcName) {
		String pronunciation = PronunciationHelperDictionary.PRONUNCIATIONS.get(npcName);
		client.addChatMessage(ChatMessageType.GAMEMESSAGE,
				"", npcName + " is pronounced: " + pronunciation, null);
	}

	//todo best solution may be : if npc name changes, and text is already coloured, dont make any more subsitutions
	//untill the name changes again just to be safe.
	private void handleDialogueWidget(Widget widget)
	{
		if (widget == null) return;

		String text = widget.getText();
		if (!text.equals(lastTextProcessed) && !weModifiedText)
		{
			log.info("Setting original text to: " + text);
			originalText = text;
		}

		if (originalText == null || originalText.isEmpty()) return;

		//this check is needed or we will keep replacing the word forever overflowing the message container
		if (!text.equals(lastTextProcessed))
		{
			log.info("text is: " + text);
			log.info("last text process is: " + lastTextProcessed);
			String newText = originalText;

			for (Map.Entry<String, String> entry : PronunciationHelperDictionary.PRONUNCIATIONS.entrySet())
			{
				String word = entry.getKey();
				String pronunciation = entry.getValue();

				//make sure we can actually match entries when casing doesnt match the dictionary
				Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(newText);

				StringBuffer sb = new StringBuffer();
				while (matcher.find())
				{
					//the reason we do it this way is to preserve the original casing of the word in the text.
					//The previous approach would sometimes capitalize words that were not originally capitalized
					String matchedWord = matcher.group();
					String replacement = getReplacementText(matchedWord, pronunciation);
					matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
				}
				matcher.appendTail(sb);
				newText = sb.toString();
			}

			if (!newText.equals(text))
			{
				widget.setText(newText);
				widget.revalidate();
			}
			lastTextProcessed = newText;
			weModifiedText = true;
		}
		else{
			lastTextProcessed = text;
			weModifiedText = false;
		}

	}

	//returns the text to replace for the matched word eligible for pronunciation based on config options
	private String getReplacementText(String matchedWord, String pronunciation) {

		Color pronunciationColor = config.pronunciationColor();
		String pronunciationColorHex = String.format("%02x%02x%02x", pronunciationColor.getRed(), pronunciationColor.getGreen(), pronunciationColor.getBlue());

		Color eligiblePronunciationColor = config.pronunciationHighlightColor();
		String eligiblePronunciationColorHex = String.format("%02x%02x%02x", eligiblePronunciationColor.getRed(), eligiblePronunciationColor.getGreen(), eligiblePronunciationColor.getBlue());

		if (showOnlyTranslation) { //i.e translation hotkey is being held
	        return "<col=" + pronunciationColorHex + ">" + pronunciation + "</col>";
	    } else if (config.alwaysShow()) {
	        return matchedWord + " (<col=" + pronunciationColorHex + ">" + pronunciation + "</col>)";
	    } else if (config.showPronunciationHighlight()) {
	        return "<col=" + eligiblePronunciationColorHex + ">" + matchedWord + "</col>";
	    }

		return matchedWord;
	}

	private boolean eitherDialogueWidgetVisisble() {
		Widget npcDialogue = client.getWidget(ComponentID.DIALOG_NPC_TEXT);
		Widget playerDialogue = client.getWidget(ComponentID.DIALOG_PLAYER_TEXT);
		return (npcDialogue != null && !npcDialogue.isHidden()) ||
				(playerDialogue != null && !playerDialogue.isHidden());
	}

	//returns weather text has <col=..> in it or not
	private boolean isUnmodifiedText(String text) {
		return !text.contains("<col=");
	}

	@Provides
	PronunciationHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PronunciationHelperConfig.class);
	}
}
