function jevRuntimeSpecs(){
  return [
    {key:"judge_provider",label:"Judge Provider",options:[
      ["openrouter","OpenRouter"],
      ["typesafe","TypeSafe / Jev"],
      ["custom","Custom"]
    ]},
    {key:"judge_base_url",label:"Judge Base URL"},
    {key:"judge_model",label:"Judge 模型"},
    {key:"judge_api_key",label:"Judge API Key",secret:true},

    {key:"reply_base_url",label:"Reply Base URL"},
    {key:"reply_model",label:"Reply 模型"},
    {key:"reply_api_key",label:"Reply API Key",secret:true,note:"留空保持独立 Vault 中已保存的 Reply Key。"},

    {key:"vision_base_url",label:"Vision Base URL"},
    {key:"vision_model",label:"Vision 模型"},
    {key:"vision_api_key",label:"Vision API Key",secret:true,note:"DeepSeek 官方当前不提供 JEV 这里所需的视觉路由。"}
  ];
}
