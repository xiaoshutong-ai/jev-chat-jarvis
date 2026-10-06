  if(projectKey==="jev-chat-jarvis"){
    if(spec.key.startsWith("judge_"))return "judge";
    if(spec.key.startsWith("reply_"))return "reply";
    if(spec.key.startsWith("vision_"))return "vision";
  }
